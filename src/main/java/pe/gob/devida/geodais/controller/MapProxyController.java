package pe.gob.devida.geodais.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import jakarta.servlet.http.HttpServletRequest;
import pe.gob.devida.geodais.service.ServicioConfiguracionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;

@RestController
@RequestMapping("/api/mapas")
public class MapProxyController {
    private static final Logger logger = LoggerFactory.getLogger(MapProxyController.class);
    private static final String ARCGIS_USER = "Jorge2025";
    private static final String ARCGIS_PASS = "Jorge2025*";
    private static final String ARCGIS_TOKEN_URL = "https://siscod.devida.gob.pe/portal/sharing/rest/generateToken";
    private static final int TOKEN_EXPIRATION_MINUTES = 60;
    private final ServicioConfiguracionService configService;
    private final RestTemplate restTemplate;
    private String cachedToken = null;
    private long tokenExpiry = 0;
    public MapProxyController(ServicioConfiguracionService configService, RestTemplate restTemplate) {
        this.configService = configService;
        this.restTemplate = restTemplate;
    }
    private synchronized String obtenerTokenArcGIS(HttpServletRequest request) {
        if (cachedToken != null && System.currentTimeMillis() < (tokenExpiry - 60000)) {
            return cachedToken;
        }
        logger.info("Solicitando nuevo token de acceso al Portal de DEVIDA...");
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("f", "json");
            params.add("username", ARCGIS_USER);
            params.add("password", ARCGIS_PASS);
            params.add("client", "referer");
            params.add("referer", request.getRequestURL().toString());
            params.add("expiration", String.valueOf(TOKEN_EXPIRATION_MINUTES));
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);
            JsonNode response = restTemplate.postForObject(ARCGIS_TOKEN_URL, entity, JsonNode.class);
            if (response != null && response.has("token")) {
                this.cachedToken = response.get("token").asText();
                this.tokenExpiry = System.currentTimeMillis() + (TOKEN_EXPIRATION_MINUTES * 60 * 1000);
                return this.cachedToken;
            }
            throw new RuntimeException("No se obtuvo token del portal");
        } catch (Exception e) {
            logger.error("Error en generación de token: {}", e.getMessage());
            throw new RuntimeException("Error al autenticarse con el Portal");
        }
    }

    private URI buildArcGISUrl(String baseUrlArcGIS, HttpServletRequest request) {
        String token = obtenerTokenArcGIS(request);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrlArcGIS);
        request.getParameterMap().forEach((key, values) -> {
            if (!key.equalsIgnoreCase("token") && !key.equalsIgnoreCase("f")) {
                for (String value : values) { 
                    builder.queryParam(key, value); 
                }
            }
        });       
        String f = request.getParameter("f");
        builder.queryParam("f", (f != null) ? f : "json");
        builder.queryParam("token", token);       
        return builder.build().encode().toUri();
    }
    @GetMapping(value = {"/capa", "/capa/"})
    public ResponseEntity<String> proxyServiceBase(HttpServletRequest request) {
        try {
            String urlBase = configService.getUrlMapaCultivos();
            URI finalUrl = buildArcGISUrl(urlBase, request);
            return processArcGISResponse(restTemplate.getForEntity(finalUrl, String.class));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"Error en root metadata\"}");
        }
    }
    @RequestMapping(value = {"/capa/legend", "/capa/legend/"}, method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> proxyLegend(HttpServletRequest request) {
        try {
            String urlBase = configService.getUrlMapaCultivos().replaceAll("/$", "");
            URI finalUrl = buildArcGISUrl(urlBase + "/legend", request);
            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            return processArcGISResponse(restTemplate.exchange(finalUrl, method, null, String.class));
        } catch (HttpStatusCodeException e) {
            logger.error("[PROXY-LEGEND] Error remoto ArcGIS {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("[PROXY-LEGEND] Error crítico: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"Error en leyenda\"}");
        }
    }
    @GetMapping(value = {"/capa/{layerId:[0-9]+}", "/capa/{layerId:[0-9]+}/"})
    public ResponseEntity<String> proxyLayerBase(@PathVariable int layerId, HttpServletRequest request) {
        try {
            ResponseEntity<String> cached = configService.getCachedMetadata(layerId);
            if (cached != null) return cached;
            String urlBase = configService.getUrlMapaCultivos().replaceAll("/$", "");
            URI finalUrl = buildArcGISUrl(urlBase + "/" + layerId, request);
            ResponseEntity<String> response = processArcGISResponse(restTemplate.getForEntity(finalUrl, String.class));            
            if (response.getStatusCode().is2xxSuccessful()) {
                configService.putMetadataInCache(layerId, response);
            }
            return response;
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"Error en metadatos\"}");
        }
    }
    @RequestMapping(value = {"/capa/{layerId:[0-9]+}/query", "/capa/{layerId:[0-9]+}/query/"}, method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> proxyQuery(@PathVariable int layerId, HttpServletRequest request) {
        try {
            String urlBase = configService.getUrlMapaCultivos().replaceAll("/$", "");
            URI finalUrl = buildArcGISUrl(urlBase + "/" + layerId + "/query", request);
            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            return processArcGISResponse(restTemplate.exchange(finalUrl, method, null, String.class));
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"Error en consulta\"}");
        }
    }
    @RequestMapping(value = {"/export", "/export/", "/capa/export", "/capa/export/"}, method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> proxyExport(HttpServletRequest request) {
        try {
            String urlBase = configService.getUrlMapaCultivos().replaceAll("/$", "");
            URI finalUrl = buildArcGISUrl(urlBase + "/export", request);            
            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            ResponseEntity<byte[]> response = restTemplate.exchange(finalUrl, method, null, byte[].class);            
            HttpHeaders headers = new HttpHeaders();
            if (response.getHeaders().getContentType() != null) {
                headers.setContentType(response.getHeaders().getContentType());
            }
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    @SuppressWarnings("null")
	private ResponseEntity<String> processArcGISResponse(ResponseEntity<String> response) {
        String body = response.getBody();
        org.springframework.http.HttpStatusCode statusCode = response.getStatusCode();        
        if (body != null && body.contains("\"error\"") && body.contains("\"code\"")) {
            if (body.contains("498") || body.contains("499") || body.contains("Invalid token")) {
                statusCode = HttpStatus.UNAUTHORIZED;
                synchronized(this) { this.cachedToken = null; }
            } else {
                statusCode = HttpStatus.BAD_REQUEST;
            }
        }
        return ResponseEntity.status(statusCode)
                .header(HttpHeaders.CONTENT_TYPE, response.getHeaders().getContentType() != null ? 
                        response.getHeaders().getContentType().toString() : MediaType.APPLICATION_JSON_VALUE)
                .body(body);
    }
}

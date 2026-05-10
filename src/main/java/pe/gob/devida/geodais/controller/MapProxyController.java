package pe.gob.devida.geodais.controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate; 
import org.springframework.web.util.UriComponentsBuilder;
import jakarta.servlet.http.HttpServletRequest;
import pe.gob.devida.geodais.service.ServicioConfiguracionService;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.HttpEntity;
import com.fasterxml.jackson.databind.JsonNode;

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

    // Caché simple para el token para evitar saturar el Portal
    private String cachedToken = null;
    private long tokenExpiry = 0;

    public MapProxyController(ServicioConfiguracionService configService, RestTemplate restTemplate) {
        this.configService = configService;
        this.restTemplate = restTemplate; 
    }    

    private synchronized String obtenerTokenArcGIS(HttpServletRequest request) {
        // Retornar token cacheado si aún es válido (con margen de seguridad de 1 minuto)
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
            // Cambiamos a 'referer' para mayor compatibilidad en el proxy
            params.add("client", "referer");
            params.add("referer", request.getRequestURL().toString());
            params.add("expiration", String.valueOf(TOKEN_EXPIRATION_MINUTES));

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);
            JsonNode response = restTemplate.postForObject(ARCGIS_TOKEN_URL, entity, JsonNode.class);

            if (response != null) {
                if (response.has("token")) {
                    this.cachedToken = response.get("token").asText();
                    // Calculamos la expiración localmente basado en los minutos solicitados
                    this.tokenExpiry = System.currentTimeMillis() + (TOKEN_EXPIRATION_MINUTES * 60 * 1000);
                    logger.info("Token de ArcGIS obtenido exitosamente. Vence en {} min.", TOKEN_EXPIRATION_MINUTES);
                    return this.cachedToken;
                } else if (response.has("error")) {
                    logger.error("Error devuelto por el Portal de ArcGIS: {}", response.get("error").toString());
                }
            }
            
            logger.error("La respuesta del portal no contiene un token. Respuesta completa: {}", response);
            throw new RuntimeException("Error al autenticarse con el Portal de DEVIDA");
        } catch (Exception e) {
            // Diagnóstico de errores de Certificado SSL
            if (e.getMessage() != null && (e.getMessage().contains("PKIX path building failed") || e.getMessage().contains("SSL"))) {
                logger.error("ERROR DE SSL: El backend no confía en el certificado de DEVIDA ({}). " +
                             "Causa: {}", ARCGIS_TOKEN_URL, e.getMessage());
            } else {
                logger.error("Fallo crítico en la generación de token: {}", e.getMessage());
            }
            throw new RuntimeException("Error al autenticarse con el Portal de DEVIDA");
        }
    }

    private String buildArcGISUrl(String baseUrlArcGIS, HttpServletRequest request) {    
        String token = obtenerTokenArcGIS(request);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrlArcGIS);
        
        // Copiamos parámetros originales evitando duplicados de token o formato
        request.getParameterMap().forEach((key, values) -> {
            if (!key.equalsIgnoreCase("token") && !key.equalsIgnoreCase("f")) {
                for (String value : values) { builder.queryParam(key, value); }
            }
        });
        
        // Forzamos formato JSON a menos que sea exportación de imagen
        String f = request.getParameter("f");
        builder.queryParam("f", (f != null) ? f : "json");
        
        builder.queryParam("token", token);
        return builder.build().toUriString(); 
    }

    /**
     * Proxy para la metadata raíz del servicio (MapServer).
     * Necesario para que el SDK de ArcGIS valide el servicio completo.
     */
    @GetMapping(value = {"/capa", "/capa/"})
    public ResponseEntity<String> proxyServiceBase(HttpServletRequest request) {
        try {
            String urlBase = configService.getUrlMapaCultivos();
            String finalUrl = buildArcGISUrl(urlBase, request);

            logger.info("[PROXY-SERVICE] Solicitando Root Metadata: {}", finalUrl);
            ResponseEntity<String> response = restTemplate.getForEntity(finalUrl, String.class);

            return processArcGISResponse(response);
        } catch (HttpStatusCodeException e) {
            logger.error("[PROXY-SERVICE] Error HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("[PROXY-SERVICE] Error crítico: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Error al conectar con la raíz del servicio de DEVIDA.\"}");
        }
    }

    @GetMapping(value = {"/capa/{layerId}", "/capa/{layerId}/"}) 
    public ResponseEntity<String> proxyLayerBase(
            @PathVariable int layerId,
            HttpServletRequest request) {        
        try {
            ResponseEntity<String> cached = configService.getCachedMetadata(layerId);
            if (cached != null) {
                logger.debug("Sirviendo metadatos de capa {} desde caché interna.", layerId);
                return cached;
            }

            String urlBase = configService.getUrlMapaCultivos().replaceAll("/$", "");    
            String baseUrlArcGIS = String.format("%s/%d", urlBase, layerId);    
            String finalArcGISUrl = buildArcGISUrl(baseUrlArcGIS, request);
            
            logger.info("[PROXY-METADATA] Solicitando a ArcGIS: {}", finalArcGISUrl);
            ResponseEntity<String> response = this.restTemplate.getForEntity(finalArcGISUrl, String.class);
            logger.info("[PROXY-METADATA] Respuesta de ArcGIS recibida. Status: {}", response.getStatusCode());
            
            if (response.getBody() != null && response.getBody().trim().startsWith("<!DOCTYPE html>")) {
                logger.error("[PROXY-METADATA] Error: Se recibió HTML para la capa {}. No se puede cachear.", layerId);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"Error al cargar metadatos de la capa.\"}");
            }

            ResponseEntity<String> finalResponse = processArcGISResponse(response);
            
            if (finalResponse.getStatusCode().is2xxSuccessful()) {
                configService.putMetadataInCache(layerId, finalResponse);
            }
            
            return finalResponse;

        } catch (HttpStatusCodeException e) {
            logger.error("[PROXY-METADATA] ArcGIS devolvió un error HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("[PROXY-METADATA] Error crítico en el proxy: {}", e.getMessage());
            String errorMsg = e.getMessage().contains("Portal de DEVIDA") ? e.getMessage() : "Error al conectar con el servidor de DEVIDA.";
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"" + errorMsg + "\"}");
        }
    }
  
    @GetMapping(value = {"/capa/{layerId}/query", "/capa/{layerId}/query/"})
    public ResponseEntity<String> proxyQuery(
            @PathVariable int layerId,
            HttpServletRequest request) {         
        try {
            String urlBase = configService.getUrlMapaCultivos().replaceAll("/$", "");    
            String baseUrlArcGIS = String.format("%s/%d/query", urlBase, layerId);    
            String finalArcGISUrl = buildArcGISUrl(baseUrlArcGIS, request);
            
            // Ocultamos el token en los logs por seguridad
            logger.info("[PROXY-QUERY] Solicitando query a ArcGIS para capa {}", layerId);
            ResponseEntity<String> response = this.restTemplate.getForEntity(finalArcGISUrl, String.class);
            logger.info("[PROXY-QUERY] Respuesta de ArcGIS recibida. Status: {}", response.getStatusCode());
            return processArcGISResponse(response);

        } catch (HttpStatusCodeException e) {
            logger.error("[PROXY-QUERY] ArcGIS devolvió un error HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("[PROXY-QUERY] Error crítico en consulta: {}", e.getMessage());
            String errorMsg = e.getMessage().contains("Portal de DEVIDA") ? e.getMessage() : "Error al procesar la consulta geográfica.";
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"" + errorMsg + "\"}");
        }
    }

    @GetMapping(value = {"/export", "/export/"})
    public ResponseEntity<byte[]> proxyExport(
            HttpServletRequest request) {
        try {
            String urlBase = configService.getUrlMapaCultivos().replaceAll("/$", "");
            String finalUrl = buildArcGISUrl(urlBase + "/export", request);
            
            logger.info("[PROXY-EXPORT] Solicitando imagen a ArcGIS: {}", finalUrl);
            ResponseEntity<byte[]> response = restTemplate.exchange(finalUrl, HttpMethod.GET, null, byte[].class);
            logger.info("[PROXY-EXPORT] Respuesta de ArcGIS recibida. Status: {}", response.getStatusCode());
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(response.getHeaders().getContentType());
            headers.setContentLength(response.getHeaders().getContentLength());
            
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        } catch (HttpStatusCodeException e) {
            logger.error("[PROXY-EXPORT] ArcGIS devolvió un error HTTP {} al exportar.", e.getStatusCode());
            return ResponseEntity.status(e.getStatusCode()).build();
        } catch (Exception e) {
            logger.error("[PROXY-EXPORT] Error crítico al exportar imagen: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Procesa la respuesta de ArcGIS para detectar errores contenidos en un HTTP 200.
     */
    private ResponseEntity<String> processArcGISResponse(ResponseEntity<String> response) {
        String body = response.getBody();
        org.springframework.http.HttpStatusCode statusCode = response.getStatusCode();
        
        // ArcGIS suele devolver errores con HTTP 200 y un JSON con campo "error"
        if (body != null && body.contains("\"error\"") && body.contains("\"code\"")) {
            logger.warn("[PROXY] ArcGIS retornó un error lógico en el cuerpo del mensaje: {}", body);
            
            // Si el error es de token inválido o expirado (498/499), forzamos 401 para limpiar la sesión
            if (body.contains("498") || body.contains("499") || body.contains("Invalid token")) {
                statusCode = HttpStatus.UNAUTHORIZED;
                synchronized(this) { this.cachedToken = null; }
            } else {
                // Otros errores (permisos, capa no encontrada) se marcan como 400
                statusCode = HttpStatus.BAD_REQUEST;
            }
        }

        return ResponseEntity.status(statusCode)
                .header(HttpHeaders.CONTENT_TYPE, response.getHeaders().getContentType() != null ? 
                        response.getHeaders().getContentType().toString() : MediaType.APPLICATION_JSON_VALUE)
                .body(body);
    }
}
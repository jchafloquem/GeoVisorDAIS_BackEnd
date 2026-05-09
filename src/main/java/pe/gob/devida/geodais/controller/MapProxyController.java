package pe.gob.devida.geodais.controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate; 
import org.springframework.web.util.UriComponentsBuilder;
import jakarta.servlet.http.HttpServletRequest;
import pe.gob.devida.geodais.service.ServicioConfiguracionService;
import java.util.Map;


@RestController
@RequestMapping("/api/mapas")
public class MapProxyController {
    private final ServicioConfiguracionService configService;
    private final RestTemplate restTemplate;
    public MapProxyController(ServicioConfiguracionService configService, RestTemplate restTemplate) {
        this.configService = configService;
        this.restTemplate = restTemplate; 
    }    
    @SuppressWarnings("null")
    private String buildArcGISUrl(String baseUrlArcGIS, HttpServletRequest request, boolean isQueryEndpoint) {    
        Map<String, String[]> parameterMap = request.getParameterMap();    
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrlArcGIS);        
        boolean hasFormatParameter = false;    
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String key = entry.getKey();
            if (key.equalsIgnoreCase("f")) {
                hasFormatParameter = true;
            }
            for (String value : entry.getValue()) {    
                builder.queryParam(key, value);
            }
        }    
        if (!hasFormatParameter) {
             builder.queryParam("f", "json");
        }
        return builder.build().toUriString(); 
    }
    
	@SuppressWarnings("null")
    @GetMapping("/capa/{layerId}") 
    public ResponseEntity<String> proxyLayerBase(
            @PathVariable int layerId,
            HttpServletRequest request) {        
        try {    
            String urlBase = configService.getUrlMapaCultivos();    
            String baseUrlArcGIS = String.format("%s/%d", urlBase, layerId);    
            String finalArcGISUrl = buildArcGISUrl(baseUrlArcGIS, request, false);
            System.out.println("DEBUG: Llamando a ArcGIS para metadatos (Oculto): " + finalArcGISUrl);    
            ResponseEntity<String> response = this.restTemplate.getForEntity(finalArcGISUrl, String.class);
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(response.getBody());
        } catch (Exception e) {
            System.err.println("Error en proxyLayerBase (Metadatos): " + e.getMessage());
            return ResponseEntity
                .internalServerError()
                .body("{\"error\": \"Error interno al obtener metadatos: " + e.getMessage() + "\"}");
        }
    }
  
    @SuppressWarnings("null")
    @GetMapping("/capa/{layerId}/query")
    public ResponseEntity<String> proxyQuery(
            @PathVariable int layerId,
            HttpServletRequest request) {         
        try {
            String urlBase = configService.getUrlMapaCultivos();    
            String baseUrlArcGIS = String.format("%s/%d/query", urlBase, layerId);    
            String finalArcGISUrl = buildArcGISUrl(baseUrlArcGIS, request, true);
            System.out.println("DEBUG: Llamando a ArcGIS (Oculto): " + finalArcGISUrl);
            ResponseEntity<String> response = this.restTemplate.getForEntity(finalArcGISUrl, String.class);
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(response.getBody());
        } catch (Exception e) {
            System.err.println("Error en proxyQuery: " + e.getMessage());    
            String errorMessage = "Error interno: No se pudo conectar al servicio ArcGIS.";
            if (e.getMessage() != null) {
                 errorMessage += " Detalle: " + e.getMessage();
            }
            return ResponseEntity
                .internalServerError()
                .body("{\"error\": \"" + errorMessage + "\"}");
        }
    }
}
package pe.gob.devida.geodais.service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.ResponseEntity;
import pe.gob.devida.geodais.mapper.ServicioMapper;
import pe.gob.devida.geodais.model.Servicio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ServicioConfiguracionService {

    private static final Logger logger = LoggerFactory.getLogger(ServicioConfiguracionService.class);

    private final ServicioMapper servicioMapper;
    private final WebClient webClient;
    private final Map<Integer, ResponseEntity<String>> metadataCache = new ConcurrentHashMap<>();

    public ServicioConfiguracionService(ServicioMapper servicioMapper, WebClient.Builder webClientBuilder) {
        this.servicioMapper = servicioMapper;
        this.webClient = webClientBuilder.build();
    }

    public String getUrlMapaCultivos() {
        final String NOMBRE_SERVICIO = "CULTIVOS_PRODUCCION_GEODAIS";
        
        Servicio servicio = servicioMapper.findByNombre(NOMBRE_SERVICIO);
        
        if (servicio != null) {
            return servicio.getUrl(); 
        } else {
            throw new RuntimeException("Error de configuración: La clave '" + NOMBRE_SERVICIO + "' no se encontró en la tabla T_SERVICIO.");
        }
    }

    @SuppressWarnings("null")
	public String verificarYObtenerUrlBase() {
        String urlBase = getUrlMapaCultivos(); 
        try {
            webClient.head()
                .uri(urlBase)
                .retrieve()
                .toBodilessEntity()
                .block(); 
            return urlBase;            
        } catch (Exception e) {
            throw new RuntimeException(
                "❌ Falló la conexión (HEAD) al servicio externo: " + urlBase + ". Verifique la URL y el firewall. Error: " + e.getMessage()
            );
        }
    }

    public ResponseEntity<String> getCachedMetadata(int layerId) {
        return metadataCache.get(layerId);
    }

    public void putMetadataInCache(int layerId, ResponseEntity<String> response) {
        // Solo cacheamos si la respuesta de ArcGIS fue exitosa (200 OK)
        if (response.getStatusCode().is2xxSuccessful()) {
            metadataCache.put(layerId, response);
        }
    }

    // Limpieza automática de la caché cada 1 hora (3,600,000 ms)
    // Esto obliga al sistema a consultar metadatos frescos periódicamente
    @Scheduled(fixedRate = 3600000)
    public void clearMetadataCache() {
        logger.info("🧹 Expirando caché de metadatos de capas (TTL alcanzado)...");
        metadataCache.clear();
    }
}
package pe.gob.devida.geodais.service;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import pe.gob.devida.geodais.mapper.ServicioMapper;
import pe.gob.devida.geodais.model.Servicio;

@Service
public class ServicioConfiguracionService {

    private final ServicioMapper servicioMapper;
    private final WebClient webClient;

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
}
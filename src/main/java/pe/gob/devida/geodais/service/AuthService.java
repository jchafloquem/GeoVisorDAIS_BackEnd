package pe.gob.devida.geodais.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory; 
import pe.gob.devida.geodais.model.AuthRequest;
import pe.gob.devida.geodais.model.AuthResponse;
import pe.gob.devida.geodais.model.ServiceAuthConfig;
import pe.gob.devida.geodais.model.UserSession;
import pe.gob.devida.geodais.repository.UserSessionRepository;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;


@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final ConfigService configService;
    private final UserSessionRepository userSessionRepository;
    private final WebClient webClient;

    public AuthService(ConfigService configService,
                       UserSessionRepository userSessionRepository,
                       WebClient.Builder webClientBuilder) {
        this.configService = configService;
        this.userSessionRepository = userSessionRepository;
        this.webClient = webClientBuilder.build(); 
    }

    
	
	@SuppressWarnings("null")
	@Transactional
    public AuthResponse authenticate(AuthRequest request) {
        
        ServiceAuthConfig config = configService.getConfig("AUTENTICACION_SIGA");
        
        String url = config.getServiceUrl();

        AuthResponse response = null;
        try {
        
            Mono<AuthResponse> responseMono = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AuthResponse.class);
            
        
            response = responseMono.block(Duration.ofSeconds(10)); 
        } catch (Exception e) {
            logger.error("Error de comunicación con el servicio de autenticación para usuario {}", request.getLogin(), e);
            throw new SecurityException("Error de comunicación con el servicio de autenticación: " + e.getMessage());
        }

        
        if (response != null && Boolean.TRUE.equals(response.getIsAuthenticated())) {

        
            String loginUpper = request.getLogin() != null ? request.getLogin().toUpperCase() : null;
            Optional<UserSession> existingSession = userSessionRepository.findByLoginAndFechaHoraSalidaIsNull(loginUpper);

            if (existingSession.isPresent()) {

                UserSession oldSession = existingSession.get();
               
                oldSession.setFechaHoraSalida(LocalDateTime.now()); 
                userSessionRepository.save(oldSession); 
                logger.warn("Sesión anterior activa finalizada automáticamente para el usuario: {}", request.getLogin());
            }
            
            UserSession newSession = new UserSession();


            newSession.setLogin(loginUpper);
            newSession.setNombreCompleto(response.getFullName());
            newSession.setFechaHoraIngreso(LocalDateTime.now()); 
            

            String coord = (request.getCoordenadaIngreso() == null || request.getCoordenadaIngreso().trim().isEmpty()) 
                       ? "0,0"
                       : request.getCoordenadaIngreso();
                       
            newSession.setCoordenadaIngreso(coord);


            userSessionRepository.save(newSession); 

            logger.info("Autenticación exitosa. Nueva sesión iniciada para el usuario: {} en coordenada: {}", request.getLogin(), coord); 

        } else if (response != null) {
            logger.warn("Fallo de credenciales para el usuario: {}", request.getLogin());
            throw new SecurityException("Credenciales no válidas según el WS externo.");
        } else {
             logger.error("El servicio de autenticación no devolvió respuesta para el usuario: {}", request.getLogin());
             throw new SecurityException("El servicio de autenticación no devolvió respuesta.");
        }

        return response;
    }
    
    
	@SuppressWarnings("unused")
	@Transactional
    public void logout(String login) {
    	
		String loginUpper = (login != null) ? login.trim().toUpperCase() : null;
    	logger.info("Iniciando proceso de logout para el usuario: {}", loginUpper);

            Optional<UserSession> activeSession = userSessionRepository.findByLoginAndFechaHoraSalidaIsNull(loginUpper);

        if (activeSession.isPresent()) {
            UserSession session = activeSession.get();
    
            session.setFechaHoraSalida(java.time.LocalDateTime.now());
            userSessionRepository.save(session);
            logger.info("Sesión cerrada exitosamente para el usuario: {}", loginUpper);
        } else {
            logger.warn("Intento de logout de usuario sin sesión activa (posiblemente ya cerrada o no autenticado): {}", loginUpper);
    
        }
    }

    @Transactional(readOnly = true)
    public boolean isSessionActive(String login) {
        if (login == null) return false;
        String loginUpper = login.trim().toUpperCase();
        return userSessionRepository.findByLoginAndFechaHoraSalidaIsNull(loginUpper).isPresent();
    }
}
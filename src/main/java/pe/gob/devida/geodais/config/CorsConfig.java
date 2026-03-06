package pe.gob.devida.geodais.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration 
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**") // Aplica a todas las rutas de la API
            // Define todos los orígenes permitidos
            .allowedOrigins(
                "http://localhost:4200",           // 1. Servidor de desarrollo de Angular
                "https://sisqa.devida.gob.pe",     // 2. Entorno de QA
                "https://sistemas.devida.gob.pe"   // 3. Entorno de Producción (basado en tu proxy.config.js)
            ) 
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Métodos HTTP permitidos
            .allowedHeaders("*") // Permite cualquier cabecera en la petición
            .allowCredentials(true); // Permite el envío de cookies y cabeceras de autenticación
    }
}

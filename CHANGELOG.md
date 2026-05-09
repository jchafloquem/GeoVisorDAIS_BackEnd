# Changelog

Todos los cambios notables en este proyecto serán documentados en este archivo.

El formato está basado en [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
y este proyecto se adhiere a [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-05-09

### Añadido
- Inicialización del proyecto con **Spring Boot 3.5.7** y **Java 21**.
- Módulo de autenticación (`AuthService`) que integra validación contra servicios externos (SIGA) mediante `WebClient`.
- Sistema de gestión de sesiones de usuario (`UserSession`) con persistencia en base de datos, incluyendo seguimiento de fecha/hora de ingreso, salida y coordenadas.
- Configuración de seguridad robusta con **JWT (JSON Web Tokens)** utilizando la librería `jjwt` v0.12.6.
- Implementación de persistencia de datos con **Spring Data JPA** y soporte para **Oracle (OJDBC11)**.
- Mapeo de configuraciones de servicios mediante **MyBatis**.
- Documentación interactiva de la API con **OpenAPI/Swagger** (Springdoc OpenAPI v2.5.0).
- Configuración de **CORS** personalizada para entornos de Desarrollo (Local), QA y Producción.
- Soporte para empaquetado en formato **WAR** para despliegue en servidores Tomcat externos.
- Automatización de construcción para incluir recursos del frontend de **Angular** dentro del paquete final.

### Cambiado
- Configuración del `maven-surefire-plugin` para permitir instrumentación de agentes (Byte Buddy) durante las pruebas unitarias.

### Seguridad
- Lógica de logout para invalidar sesiones activas en base de datos.
- Validación de credenciales delegada a servicios web externos con manejo de excepciones de seguridad.

---
*Nota: Este es el lanzamiento inicial del Módulo de Seguridad y Visor GeoDAIS.*

[1.0.0]: https://github.com/devida/geodais/releases/tag/v1.0.0
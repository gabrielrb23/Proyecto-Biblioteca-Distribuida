# Proyecto Biblioteca Distribuida

Este proyecto es una implementación distribuida de una biblioteca, desarrollado en Java y gestionado con Maven.

## Requisitos

- Java 8 o superior
- Maven

## Estructura del proyecto

- Código fuente: `src/main/java/edu/javeriana/biblioteca/`
- Archivos de configuración: `src/main/resources/`
- Archivos de prueba: `src/main/resources/test-files/`

## Ejecución de los procesos principales

Para ejecutar los diferentes procesos del sistema, utiliza los siguientes comandos en la raíz del proyecto:

### 1. LoadManager

```
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager"
```

### 2. ReturnActor

```
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.ReturnActor"
```

### 3. RenewalActor

```
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.RenewalActor"
```

### 4. SolicitingProcess

Puedes ejecutar el proceso de solicitud pasando como argumento el archivo de prueba que desees:

```
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.SolicitingProcess" -Dexec.args="src/main/resources/test-files/S01.txt"
```

Cambia `S01.txt` por el archivo de prueba que desees utilizar (`S02.txt`, `S03.txt`, etc.).

## Configuración para múltiples máquinas

Para ejecutar el sistema en múltiples máquinas, es necesario configurar correctamente el archivo `app.properties` ubicado en `src/main/resources/`.

### Configuración actual (una sola máquina)

```properties
gc.rep=tcp://127.0.0.1:5555
gc.pub=tcp://127.0.0.1:5556

actor.return.sub=tcp://127.0.0.1:5556
actor.renewal.sub=tcp://127.0.0.1:5556
```

### Configuración para múltiples máquinas

#### En la máquina que ejecuta LoadManager (servidor central):

```properties
gc.rep=tcp://*:5555
gc.pub=tcp://*:5556

actor.return.sub=tcp://IP_DEL_SERVIDOR:5556
actor.renewal.sub=tcp://IP_DEL_SERVIDOR:5556
```

#### En las máquinas que ejecutan los actores (ReturnActor, RenewalActor, SolicitingProcess):

```properties
gc.rep=tcp://IP_DEL_SERVIDOR:5555
gc.pub=tcp://IP_DEL_SERVIDOR:5556

actor.return.sub=tcp://IP_DEL_SERVIDOR:5556
actor.renewal.sub=tcp://IP_DEL_SERVIDOR:5556
```

### Pasos para configuración distribuida:

1. **Identifica la IP del servidor**: Reemplaza `IP_DEL_SERVIDOR` con la dirección IP real de la máquina que ejecutará el LoadManager.

2. **Configura el firewall**: Asegúrate de que los puertos 5555 y 5556 estén abiertos en el firewall de la máquina servidor.

3. **Verifica la conectividad**: Las máquinas cliente deben poder conectarse a la IP del servidor en los puertos especificados.

4. **Orden de ejecución**:
   - Primero ejecuta el LoadManager en la máquina servidor
   - Luego ejecuta los actores (ReturnActor, RenewalActor) en las máquinas cliente
   - Finalmente ejecuta SolicitingProcess en las máquinas que necesiten realizar solicitudes

### Ejemplo práctico:

Si el servidor tiene IP `192.168.1.100`, el archivo `app.properties` en las máquinas cliente sería:

```properties
gc.rep=tcp://192.168.1.100:5555
gc.pub=tcp://192.168.1.100:5556

actor.return.sub=tcp://192.168.1.100:5556
actor.renewal.sub=tcp://192.168.1.100:5556
```

## Notas

- Asegúrate de tener configurado correctamente el archivo `app.properties` según el tipo de despliegue.
- Los archivos de prueba se encuentran en `src/main/resources/test-files/`.
- Para entornos distribuidos, verifica que todas las máquinas tengan acceso de red entre sí.

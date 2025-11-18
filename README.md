# 📚 Proyecto Biblioteca Distribuida  
Sistema de Biblioteca con Arquitectura Distribuida, Concurrencia, Tolerancia a Fallos, Replicación y Cifrado  
**Java + ZeroMQ + PostgreSQL**

---

## 🚀 Descripción General

Este proyecto implementa un **sistema de biblioteca distribuido**, tolerante a fallos y completamente desacoplado.  
Está diseñado con **actores**, **procesos independientes**, **replicación de BD**, **failover automático**, **cifrado de mensajes** y **ZeroMQ** como middleware de mensajería.

El sistema se compone de:

- **GA – Gestor de Almacenamiento (`StorageManager`)**  
- **GC – Gestor de Carga (`LoadManager`)**  
- **Actores** (`LoanActor`, `ReturnActor`, `RenewalActor`)  
- **PS – Proceso Solicitante (`SolicitingProcess`)**  
- **BD Primaria y Secundaria** con replicación manual

✔ Cada proceso corre en su propia JVM  
✔ Comunicación con sockets ZeroMQ (REQ/REP – PUB/SUB)  
✔ Soporte para operación local y distribuida  
✔ Failover automático GA → GA secundario  
✔ Failover GC → GC secundario (desde PS)  
✔ Actores con idempotencia y reintentos  
✔ PS tolerante a fallos y rotación de GC  
✔ Transacciones ACID + `FOR UPDATE` para consistencia  
✔ **Cifrado de mensajes** entre procesos para mayor seguridad

---

## 🧩 Arquitectura General

```text
          ┌──────────────────┐
          │  SolicitingProcess│
          └───────┬──────────┘
                  │ REQ/REP (failover, cifrado)
                  ▼
        ┌──────────────────────┐
        │   Gestores de Carga  │
        │  GC Primario / Sec.  │
        └──────┬───────────────┘
               │ PUB/SUB (eventos)
               ▼
 ┌─────────────┬─────────────────────────┬─────────────────────────┐
 │         LoanActor                   ReturnActor               RenewalActor
 │             │ REQ/REP con failover hacia GA (cifrado)          │       
 └─────────────┴──────────────┬─────────┴──────────────┬──────────┘
                              │
                              ▼
                  ┌──────────────────────┐
                  │ Gestor Almacenamiento│
                  │ GA Primario / Sec.   │
                  └───────────┬──────────┘
                              │ SQL
                              ▼
             BD Primaria ←── Replicación ─→ BD Secundaria
```

---

## ⚙️ Requisitos

- Java 17+  
- Maven  
- PostgreSQL (2 bases: primaria y secundaria)  

---

## 📂 Estructura del Proyecto

```text
src/main/java/edu/javeriana/biblioteca/
├── processes/       # GA, GC, Actors, PS
├── messaging/       # Message, StorageResult, StorageCommand (cifrado)
├── replication/     # Failover y replicación BD
├── persistence/     # StorageGateway + SQL
└── common/          # AppConfig, AuditLogger, utilidades
```

Archivos de prueba:

```text
src/main/resources/test-files/S01.txt
src/main/resources/test-files/S02.txt
...
```

---

## 🛠️ Comandos de Ejecución (modo local)

> Todos estos comandos se ejecutan desde la raíz del proyecto.

### 🟩 1. GA – Gestores de Almacenamiento (Primario y Secundario)

```bash
# GA Primario
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.StorageManager" -Dga.rep=tcp://0.0.0.0:5560

# GA Secundario
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.StorageManager" -Dga.rep=tcp://0.0.0.0:5564
```

---

### 🟦 2. GC – Gestores de Carga

#### Modo Asíncrono (por defecto)

```bash
# GC Primario
mvn exec:java \
  -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" \
  -Dgc.rep=tcp://0.0.0.0:5555 \
  -Dgc.pub=tcp://0.0.0.0:5556

# GC Secundario
mvn exec:java \
  -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" \
  -Dgc.rep=tcp://0.0.0.0:5551 \
  -Dgc.pub=tcp://0.0.0.0:5552
```

#### Modo Sincrónico

```bash
# GC Primario Sync
mvn exec:java \
  -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" \
  -Dgc.rep=tcp://0.0.0.0:5555 \
  -Dgc.pub=tcp://0.0.0.0:5556 \
  -Dexec.args="sync"

# GC Secundario Sync
mvn exec:java \
  -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoadManager" \
  -Dgc.rep=tcp://0.0.0.0:5551 \
  -Dgc.pub=tcp://0.0.0.0:5552 \
  -Dexec.args="sync"
```

---

### 🟨 3. Actores (Sync o Async según argumento)

#### Modo Async (por defecto)

```bash
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoanActor"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.RenewalActor"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.ReturnActor"
```

#### Modo Sync

```bash
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.LoanActor"    -Dexec.args="--sync"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.RenewalActor" -Dexec.args="--sync"
mvn exec:java -Dexec.mainClass="edu.javeriana.biblioteca.processes.ReturnActor"  -Dexec.args="--sync"
```

---

### 🟧 4. PS – Proceso Solicitante (tolerante a fallos GC)

```bash
mvn exec:java \
  -Dexec.mainClass="edu.javeriana.biblioteca.processes.SolicitingProcess" \
  -Dexec.args="src/main/resources/test-files/S01.txt"
```

Puedes cambiar `S01.txt` por `S02.txt`, `S03.txt`, etc.

---

## 🔧 Configuración (`app.properties`)

Ejemplo de configuración **local** con failover GA y GC:

```properties
############################################
# GESTOR DE ALMACENAMIENTO (GA)
############################################
ga.rep=tcp://localhost:5560
ga.rep.endpoints=tcp://localhost:5560,tcp://localhost:5564

############################################
# GC PRIMARIO / SECUNDARIO
############################################
gc.rep=tcp://localhost:5555
gc.pub=tcp://localhost:5556

# (opcional secundario)
# gc.rep=tcp://localhost:5551
# gc.pub=tcp://localhost:5552

############################################
# ACTORES - MODO ASÍNCRONO
############################################
actor.loan.sub=tcp://localhost:5556
actor.return.sub=tcp://localhost:5556
actor.renew.sub=tcp://localhost:5556

actor.loan.req=tcp://localhost:5561
actor.return.req=tcp://localhost:5562
actor.renew.req=tcp://localhost:5563

############################################
# ACTORES - MODO SINCRÓNICO
############################################
actor.loan.rep=tcp://localhost:5557
actor.return.rep=tcp://localhost:5558
actor.renew.rep=tcp://localhost:5559

############################################
# BASES DE DATOS
############################################
db.primary.url=jdbc:postgresql://localhost:5432/BDPrimaria
db.primary.user=postgres
db.primary.pass=123

db.secondary.url=jdbc:postgresql://localhost:5432/BDSecundaria
db.secondary.user=postgres
db.secondary.pass=123

db.health.interval=1500

############################################
# PROCESO SOLICITANTE (PS)
############################################
ps.gc.endpoints=tcp://localhost:5555,tcp://localhost:5551
ps.delay.ms=500
ps.snd.timeout.ms=2000
ps.rcv.timeout.ms=2000
```

---

## 🔐 Cifrado de Mensajes

El sistema implementa **cifrado de mensajes** para proteger la comunicación entre procesos:

- Se usa una capa de mensajería basada en objetos como `Message` y `StorageCommand`.
- Antes de circular por ZeroMQ, los mensajes pueden:
  - serializarse (JSON/string)
  - cifrarse usando una clave compartida (leída desde `AppConfig`)
- El receptor:
  - descifra el payload
  - parsea el mensaje de vuelta a objetos de dominio

Esto permite:

- Proteger información sensible (usuarios, libros, operaciones)
- Evitar que un intermediario de red vea el contenido de las operaciones
- Mantener el diseño desacoplado: el cifrado se concentra en la capa de mensajería, no en la lógica de negocio.

La configuración típica del cifrado se define en `app.properties` (por ejemplo, clave, algoritmo, etc.), y se usa de forma transparente en las clases del paquete `messaging`.

---

## 🔥 Características Técnicas Implementadas

- ✅ **Failover automático GA**  
  - Health-check de BD primaria  
  - `DataSourceRouter` y `FailoverMonitor`  
  - Replicación a BD secundaria mediante `Replicator`

- ✅ **Idempotencia en operaciones de negocio**  
  - `applyLoan`, `applyReturn`, `applyRenewal` verifican estado previo  
  - Previenen duplicados en reintentos y fallos de red

- ✅ **Control de concurrencia con `FOR UPDATE`**  
  - Bloqueos de filas en `loans` y `branch_inventory`  
  - Evita condiciones de carrera en inventarios y préstamos

- ✅ **Transacciones ACID**  
  - `setAutoCommit(false)`  
  - `commit` / `rollback` centralizados  
  - Garantía de consistencia ante excepciones

- ✅ **Replicación BD Primaria → Secundaria**  
  - Se replica solo cuando la operación realmente se aplica  
  - No se replica en operaciones idempotentes

- ✅ **Failover PS → múltiples GC**  
  - `ps.gc.endpoints` admite varios GC  
  - Rotación automática si uno deja de responder  
  - Reintentos con `backoff`

- ✅ **Cifrado de mensajes entre procesos**  
  - Protección del contenido de los mensajes  
  - Claves parametrizadas por configuración  
  - Integrado en la capa de mensajería

---

## 🧪 Archivos de Prueba

Ubicados en:

```text
src/main/resources/test-files/
```

Cada archivo contiene líneas con operaciones de alto nivel:

```text
PRESTAMO,S1,U1,BK-0001
DEVOLUCION,S1,U2,BK-0031
RENOVACION,S1,U5,BK-0011
```

El `SolicitingProcess` las convierte en mensajes distribuidos y maneja el failover hacia múltiples GC.

---

## 📌 Notas

- Ejecuta cada proceso en una **consola independiente**.  
- El sistema puede desplegarse tanto **localmente** como en **múltiples máquinas** simplemente cambiando IPs y puertos en `app.properties`.  
- La arquitectura está pensada para ser **modular, extensible y tolerante a fallos**, manteniendo **consistencia fuerte** en los datos.


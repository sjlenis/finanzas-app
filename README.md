# Finanzas: asistente de finanzas personales para Android

Aplicación Android nativa, privada y 100 % local para administrar finanzas personales en Colombia (COP).
Sin servidores, sin cuentas en la nube y con mantenimiento de $0.

## Estado

| Fase | Contenido | Estado |
| --- | --- | --- |
| 1 | Análisis y arquitectura | Hecha |
| 2 | Proyecto Android, tema, navegación, Room cifrado | Hecha |
| 3 | Cuentas, movimientos, categorías, transferencias, dashboard | Hecha |
| 4 | Asistente financiero (chat, intérprete local sin internet) | Hecha |
| 5 | Detección de notificaciones Nequi / Bancolombia | Hecha (se afina con muestras reales) |
| 6 | Presupuestos, deudas, fondo de emergencia, metas, calendario | Hecha |
| 7 | Recomendaciones e inteligencia financiera | En construcción (parte A: análisis, consejos y simulador de compras) |
| 8 | Pruebas finales y APK firmado | Pendiente |

Las pantallas de funciones aún no construidas lo indican claramente; la app nunca muestra cifras de ejemplo.

## Tecnologías

Kotlin, Jetpack Compose + Material 3, Room sobre SQLCipher (base de datos cifrada), Android Keystore,
Hilt, Navigation Compose, WorkManager (próximas fases). Mínimo Android 10 (API 29).

## Estructura

```
app/                 Aplicación, navegación y arranque
core/model           Modelo en Kotlin puro (Money en centavos, tipos de movimiento)
core/common          Fechas en hora de Colombia
core/database        Room + SQLCipher, entidades, DAOs, datos iniciales
core/data            Repositorios
core/security        Clave de la base protegida con Android Keystore
core/designsystem    Tema claro/oscuro y componentes
feature/*            Inicio, Movimientos, Asistente, Presupuestos, Perfil
```

## Reglas del proyecto

- El dinero siempre es `Long` en centavos. Nunca `Float` ni `Double`.
- El saldo de una cuenta no se guarda: se calcula desde el saldo inicial y los movimientos confirmados.
- Las transferencias propias, préstamos y ajustes no cuentan como ingreso ni gasto.
- La app no declara el permiso de Internet.
- Sin WhatsApp, sin APIs bancarias no oficiales, sin guardar contraseñas bancarias.

## Compilar

GitHub Actions compila cada cambio y publica el APK de depuración como artefacto
(pestaña **Actions** → la ejecución → **finanzas-debug-apk**).

En local, con Android Studio o JDK 17 + Android SDK:

```
./gradlew test
./gradlew :app:assembleDebug
```

## Instalar en el teléfono

1. Descarga y descomprime el artefacto `finanzas-debug-apk`.
2. Copia el `.apk` al teléfono y ábrelo. Android pedirá permitir instalar apps desde esa fuente.

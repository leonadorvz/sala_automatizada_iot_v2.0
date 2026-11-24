/*
 * ==============================================================================
 * SISTEMA IOT - SALA AUTOMATIZADA (INACAP / ISO 27400)
 * Microcontrolador: ESP32 / ESP8266 / Arduino con Wi-Fi
 * ==============================================================================
 * 
 * PINES DE CONEXIÓN DE HARDWARE:
 * - Sensor DHT11 (Temperatura y Humedad): Pin GPIO 4
 * - Sensor LDR (Fotoresistor de Luz): Pin Analógico GPIO 34 (A0 en ESP8266/Arduino)
 * - Sensor PIR (Detección de Personas): Pin GPIO 13
 * - Relé Actuador (Ventilador / Extractor): Pin GPIO 23
 * 
 * LIBRERÍAS REQUERIDAS (Instalar en Arduino IDE -> Administrador de Bibliotecas):
 * 1. "DHT sensor library" de Adafruit
 * 2. "ArduinoJson" de Benoît Blanchon (v6 o v7)
 * ==============================================================================
 */

#include <WiFi.h>          // Para ESP8266 usar #include <ESP8266WiFi.h> y <ESP8266WebServer.h>
#include <WebServer.h>     // Para ESP8266 usar ESP8266WebServer
#include <DHT.h>
#include <ArduinoJson.h>

// Definición de Pines
#define PIN_DHT 4
#define TIPO_DHT DHT11
#define PIN_LDR 34
#define PIN_PIR 13
#define PIN_RELE_VENTILADOR 23

// Credenciales Wi-Fi (Modo Cliente Router)
// Ingrese aquí el nombre y clave del Wi-Fi de su sala o comparta internet desde su móvil:
const char* WIFI_SSID = "MiRedWiFi";
const char* WIFI_PASS = "MiClave123";

// Modo Punto de Acceso (Access Point si no hay router disponible)
const char* AP_SSID = "SALA_AUTOMATIZADA";
const char* AP_PASS = "12345678";

DHT dht(PIN_DHT, TIPO_DHT);
WebServer server(80);

// Variables de Estado de la Sala
float temperaturaActual = 24.0;
float humedadActual = 50.0;
int conteoPersonas = 0;
String estadoLuz = "Óptimo";
bool estadoVentilador = false;

unsigned long ultimoMuestreo = 0;

void handleGetEstado() {
  StaticJsonDocument<256> doc;
  doc["temperatura"] = temperaturaActual;
  doc["humedad"] = humedadActual;
  doc["luz"] = estadoLuz;
  doc["personas"] = conteoPersonas;
  doc["ventilador"] = estadoVentilador;

  String jsonResponse;
  serializeJson(doc, jsonResponse);
  server.send(200, "application/json", jsonResponse);
}

void handlePostVentilador() {
  if (server.hasArg("plain") == false) {
    server.send(400, "application/json", "{\"error\":\"Cuerpo JSON requerido\"}");
    return;
  }

  String body = server.arg("plain");
  StaticJsonDocument<200> doc;
  DeserializationError error = deserializeJson(doc, body);

  if (error) {
    server.send(400, "application/json", "{\"error\":\"JSON malformado\"}");
    return;
  }

  if (doc.containsKey("ventilador")) {
    estadoVentilador = doc["ventilador"].as<bool>();
    
    // Activa o desactiva el relé físico
    // Nota: la mayoría de módulos relé se activan con LOW; si su módulo es activo en HIGH, invierta el valor.
    digitalWrite(PIN_RELE_VENTILADOR, estadoVentilador ? HIGH : LOW);
    
    Serial.print("[ACTUADOR] Ventilador cambiado a: ");
    Serial.println(estadoVentilador ? "ENCENDIDO" : "APAGADO");

    server.send(200, "application/json", "{\"status\":\"OK\",\"ventilador\":" + String(estadoVentilador ? "true" : "false") + "}");
  } else {
    server.send(400, "application/json", "{\"error\":\"Campo 'ventilador' no encontrado\"}");
  }
}

void leerSensores() {
  // 1. Lectura de DHT11
  float t = dht.readTemperature();
  float h = dht.readHumidity();

  if (!isnan(t)) temperaturaActual = t;
  if (!isnan(h)) humedadActual = h;

  // 2. Lectura de Luz (LDR)
  int valorLdr = analogRead(PIN_LDR);
  if (valorLdr < 1200) {
    estadoLuz = "Óptimo";
  } else if (valorLdr < 2500) {
    estadoLuz = "Media";
  } else {
    estadoLuz = "Baja";
  }

  // 3. Lectura de Presencia (PIR)
  int hayPresencia = digitalRead(PIN_PIR);
  if (hayPresencia == HIGH) {
    conteoPersonas = 2; // O incremento según lógica de la sala
  }

  // 4. Regla de seguridad de confort térmico:
  // Si la temperatura supera los 30°C, se activa automáticamente por seguridad
  if (temperaturaActual >= 30.0 && !estadoVentilador) {
    estadoVentilador = true;
    digitalWrite(PIN_RELE_VENTILADOR, HIGH);
    Serial.println("[ALERTA] Temperatura > 30°C. Activando ventilador automáticamente.");
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n=== INICIALIZANDO SALA AUTOMATIZADA ===");

  // Configuración de Pines
  pinMode(PIN_RELE_VENTILADOR, OUTPUT);
  digitalWrite(PIN_RELE_VENTILADOR, LOW); // Apagado inicial
  pinMode(PIN_PIR, INPUT);
  dht.begin();

  // Intento de conexión Wi-Fi al Router
  WiFi.mode(WIFI_AP_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Conectando al Wi-Fi: ");
  Serial.println(WIFI_SSID);

  int intentos = 0;
  while (WiFi.status() != WL_CONNECTED && intentos < 15) {
    delay(500);
    Serial.print(".");
    intentos++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\n[OK] Conectado al Router Wi-Fi!");
    Serial.print("Dirección IP asignada: ");
    Serial.println(WiFi.localIP());
  } else {
    // Si no conecta al router, levanta su propio Punto de Acceso (Access Point)
    Serial.println("\n[AVISO] No se pudo conectar al router. Levantando Access Point propio...");
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.print("Conéctate al Wi-Fi del ESP32: ");
    Serial.println(AP_SSID);
    Serial.print("IP del Access Point: ");
    Serial.println(WiFi.softAPIP()); // Generalmente 192.168.4.1
  }

  // Configuración de Rutas HTTP para la App Android
  server.on("/estado", HTTP_GET, handleGetEstado);
  server.on("/ventilador", HTTP_POST, handlePostVentilador);

  server.begin();
  Serial.println("[OK] Servidor Web de la Sala iniciado. Listo para recibir peticiones de la App.");
}

void loop() {
  server.handleClient();

  // Muestreo cada 2 segundos
  if (millis() - ultimoMuestreo > 2000) {
    ultimoMuestreo = millis();
    leerSensores();
  }
}

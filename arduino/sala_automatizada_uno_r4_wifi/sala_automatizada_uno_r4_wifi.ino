/*
 * ==============================================================================
 * SISTEMA SALA AUTOMATIZADA - INACAP / ISO 27400
 * Placa: ARDUINO UNO R4 WIFI (Comunicacion Dual: Wi-Fi + USB Serial Bridge)
 * Umbral de Activacion Automatica del Ventilador: > 25.0 °C
 * ==============================================================================
 */

#include <WiFiS3.h>
#include <DHT.h>
#include <ArduinoJson.h>

// --- DEFINICION DE PINES ---
#define PIN_DHT           2   // Sensor DHT11 (Pin S)
#define TIPO_DHT          DHT11

#define PIN_LDR           A0  // Sensor LDR Fotoresistor (Pin AO)

#define PIN_LED_ROJO      8   // Semaforo - Pin R (> 25.0 °C)
#define PIN_LED_AMARILLO  9   // Semaforo - Pin Y (24.0 °C a 25.0 °C)
#define PIN_LED_VERDE     10  // Semaforo - Pin G (< 24.0 °C)

#define PIN_RELE          7   // Modulo Rele Songle (Pin IN - Activo en LOW)

// --- UMBRAL TERMICO AUTOMATICO ---
#define UMBRAL_VENTILADOR 25.0

// --- CONFIGURACION WI-FI ---
const char* WIFI_SSID = "TuNombreDeWiFi";
const char* WIFI_PASS = "TuClaveWiFi";

// Red Wi-Fi propia del Arduino (Access Point):
const char* AP_SSID = "SALA_AUTOMATIZADA";
const char* AP_PASS = "12345678";

DHT dht(PIN_DHT, TIPO_DHT);
WiFiServer server(80);

// --- VARIABLES GLOBALES ---
float temperatura = 24.0;
float humedad = 50.0;
int lecturaLdrActual = 0;
String estadoLuz = "Optimo";
bool ventiladorEncendido = false;
bool controlManualActivo = false;
int personasEstimadas = 2;

unsigned long ultimoMuestreo = 0;

// --- ENVIAR TELEMETRIA POR SERIAL (USB) ---
void enviarEstadoSerial() {
  StaticJsonDocument<256> doc;
  doc["temperatura"] = temperatura;
  doc["humedad"] = humedad;
  doc["luz"] = estadoLuz;
  doc["luminosidad"] = lecturaLdrActual;
  doc["personas"] = personasEstimadas;
  doc["ventilador"] = ventiladorEncendido;

  Serial.print("TELEMETRY:");
  serializeJson(doc, Serial);
  Serial.println();
}

// --- CONTROL DEL SEMAFORO FISICO ---
void actualizarSemaforo(float temp) {
  digitalWrite(PIN_LED_VERDE, LOW);
  digitalWrite(PIN_LED_AMARILLO, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);

  if (temp > UMBRAL_VENTILADOR) {
    // Alerta (> 25.0 °C): LED Rojo
    digitalWrite(PIN_LED_ROJO, HIGH);
  } else if (temp >= 24.0) {
    // Precaucion (24.0 °C a 25.0 °C): LED Amarillo
    digitalWrite(PIN_LED_AMARILLO, HIGH);
  } else {
    // Optimo (< 24.0 °C): LED Verde
    digitalWrite(PIN_LED_VERDE, HIGH);
  }
}

// --- CONTROL DEL VENTILADOR VIA RELE (ACTIVO EN LOW) ---
void accionarVentilador(bool encender) {
  ventiladorEncendido = encender;
  // El modulo rele Songle SRD-05VDC-SL-C es Activo en BAJO (LOW = Encendido, HIGH = Apagado)
  digitalWrite(PIN_RELE, encender ? LOW : HIGH);
}

// --- LECTURA PERIODICA DE SENSORES ---
void leerSensores() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();

  if (!isnan(t) && t > 0.0 && t < 80.0) {
    temperatura = t;
  }
  if (!isnan(h) && h >= 0.0 && h <= 100.0) {
    humedad = h;
  }

  lecturaLdrActual = analogRead(PIN_LDR);
  if (lecturaLdrActual < 350) {
    estadoLuz = "Optimo";
  } else if (lecturaLdrActual < 750) {
    estadoLuz = "Media";
  } else {
    estadoLuz = "Baja";
  }

  // Actualizar semaforo LED segun la temperatura real
  actualizarSemaforo(temperatura);

  // Regla automatica OT:
  // 1) Si la temperatura esta por encima de 25.0 °C, encender ventilador automaticamente
  if (temperatura > UMBRAL_VENTILADOR && !ventiladorEncendido) {
    accionarVentilador(true);
  }
  // 2) Si baja a 25.0 °C o menos y no fue forzado manualmente desde la app, apagarlo automaticamente
  else if (temperatura <= UMBRAL_VENTILADOR && !controlManualActivo && ventiladorEncendido) {
    accionarVentilador(false);
  }

  // Enviar datos actualizados por USB Serial para sincronizar con la App y Firebase
  enviarEstadoSerial();
}

// --- RECIBIR COMANDOS DESDE USB SERIAL ---
void revisarComandosSerial() {
  while (Serial.available() > 0) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.length() == 0) continue;

    if (cmd == "FAN:1" || cmd.indexOf("\"ventilador\":true") >= 0) {
      controlManualActivo = true;
      accionarVentilador(true);
      enviarEstadoSerial();
    } else if (cmd == "FAN:0" || cmd.indexOf("\"ventilador\":false") >= 0) {
      controlManualActivo = false;
      accionarVentilador(false);
      enviarEstadoSerial();
    } else if (cmd == "STATUS" || cmd == "GET") {
      enviarEstadoSerial();
    }
  }
}

// --- SETUP INICIAL ---
void setup() {
  Serial.begin(115200);
  delay(500);

  pinMode(PIN_RELE, OUTPUT);
  accionarVentilador(false); // Inicia APAGADO (PIN_RELE en HIGH)

  pinMode(PIN_LED_ROJO, OUTPUT);
  pinMode(PIN_LED_AMARILLO, OUTPUT);
  pinMode(PIN_LED_VERDE, OUTPUT);

  dht.begin();

  // Testeo visual rapido del semaforo
  digitalWrite(PIN_LED_VERDE, HIGH);
  delay(150);
  digitalWrite(PIN_LED_AMARILLO, HIGH);
  delay(150);
  digitalWrite(PIN_LED_ROJO, HIGH);
  delay(200);

  // Primera lectura inmediata de sensores
  leerSensores();

  // Configurar Wi-Fi
  if (String(WIFI_SSID) != "TuNombreDeWiFi" && String(WIFI_SSID).length() > 0) {
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    int intentos = 0;
    while (WiFi.status() != WL_CONNECTED && intentos < 10) {
      delay(400);
      intentos++;
    }
  }

  if (WiFi.status() != WL_CONNECTED) {
    WiFi.beginAP(AP_SSID, AP_PASS);
  }

  server.begin();
  enviarEstadoSerial();
}

// --- ATENCION DE PETICIONES HTTP WI-FI ---
void atenderCliente(WiFiClient& client) {
  String peticion = "";
  String cuerpo = "";
  bool finCabeceras = false;
  bool esPost = false;
  unsigned long inicio = millis();

  while (client.connected() && (millis() - inicio < 1500)) {
    if (client.available()) {
      char c = client.read();
      if (!finCabeceras) {
        peticion += c;
        if (peticion.endsWith("\r\n\r\n")) {
          finCabeceras = true;
          if (peticion.startsWith("POST /ventilador")) {
            esPost = true;
          } else {
            break;
          }
        }
      } else if (esPost) {
        cuerpo += c;
        if (cuerpo.indexOf("}") >= 0) break;
      }
    }
  }

  if (peticion.startsWith("GET /estado")) {
    StaticJsonDocument<256> doc;
    doc["temperatura"] = temperatura;
    doc["humedad"] = humedad;
    doc["luz"] = estadoLuz;
    doc["personas"] = personasEstimadas;
    doc["ventilador"] = ventiladorEncendido;

    String jsonSalida;
    serializeJson(doc, jsonSalida);

    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: application/json");
    client.println("Connection: close");
    client.print("Content-Length: ");
    client.println(jsonSalida.length());
    client.println();
    client.println(jsonSalida);
  } else if (peticion.startsWith("POST /ventilador")) {
    StaticJsonDocument<128> doc;
    deserializeJson(doc, cuerpo);

    if (doc.containsKey("ventilador")) {
      bool nuevoEstado = doc["ventilador"].as<bool>();
      controlManualActivo = nuevoEstado;
      accionarVentilador(nuevoEstado);
      enviarEstadoSerial();
    }

    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: application/json");
    client.println("Connection: close");
    client.println();
    client.println("{\"status\":\"OK\",\"ventilador\":" + String(ventiladorEncendido ? "true" : "false") + "}");
  } else {
    client.println("HTTP/1.1 404 Not Found");
    client.println("Connection: close");
    client.println();
  }

  delay(5);
  client.stop();
}

// --- BUCLE PRINCIPAL ---
void loop() {
  revisarComandosSerial();

  WiFiClient client = server.available();
  if (client) {
    atenderCliente(client);
  }

  // Muestreo de sensores cada 1.5 segundos
  if (millis() - ultimoMuestreo >= 1500) {
    ultimoMuestreo = millis();
    leerSensores();
  }
}

package edu.javeriana.biblioteca.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
	private static final Properties PROPS = new Properties();
	static {
		// Carga las propiedades desde app.properties al iniciar la clase
		try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
			if (in != null)
				PROPS.load(in);
		} catch (IOException e) {
			// Si falla la carga, detiene la ejecución
			throw new RuntimeException(e);
		}
	}

	public static String get(String key, String def) {
		// Retorna la propiedad o el valor por defecto
		return PROPS.getProperty(key, def);
	}

	public static String getEnvOrProp(String envVar, String propName, String def) {
		// Prioriza variable de entorno, luego propiedad del archivo, luego valor por
		// defecto
		String v = System.getenv(envVar);
		if (v != null && !v.isEmpty())
			return v;
		v = PROPS.getProperty(propName);
		if (v != null && !v.isEmpty())
			return v;
		return def;
	}
}

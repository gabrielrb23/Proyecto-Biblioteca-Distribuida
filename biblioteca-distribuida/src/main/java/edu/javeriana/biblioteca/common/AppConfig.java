package edu.javeriana.biblioteca.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AppConfig {
	private static final Properties PROPS = new Properties();
	static {
		try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
			if (in != null)
				PROPS.load(in);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static String get(String key, String def) {
		return PROPS.getProperty(key, def);
	}

	public static String getEnvOrProp(String envVar, String propName, String def) {
		String v = System.getenv(envVar);
		if (v != null && !v.isEmpty())
			return v;
		v = PROPS.getProperty(propName);
		if (v != null && !v.isEmpty())
			return v;
		return def;
	}
}

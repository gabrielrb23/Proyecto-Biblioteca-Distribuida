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
}

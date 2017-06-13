package cn.superman.sandbox.core.classLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class SandboxClassLoader extends ClassLoader {
	private String classPath = null;

	public SandboxClassLoader(String classPath) {
		super();
		this.classPath = classPath;
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		return loadSandboxClass(name);
	}

	public Class<?> loadSandboxClass(String name) throws ClassNotFoundException {
		String classFilePath = classPath + File.separator + name + ".class";
		FileInputStream inputStream = null;
		try {
			File file = new File(classFilePath);
			inputStream = new FileInputStream(file);
			byte[] classByte = new byte[(int) file.length()];
			inputStream.read(classByte);

			return defineClass(name, classByte, 0, classByte.length);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				inputStream.close();
			} catch (IOException e) {
			}
		}

		return null;
	}
}

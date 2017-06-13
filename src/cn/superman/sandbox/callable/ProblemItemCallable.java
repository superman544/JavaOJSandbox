package cn.superman.sandbox.callable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

import cn.superman.sandbox.core.systemInStream.ThreadInputStream;
import cn.superman.sandbox.core.systemOutStream.CacheOutputStream;
import cn.superman.sandbox.dto.ProblemResultItem;

public class ProblemItemCallable implements Callable<ProblemResultItem> {
	private Method mainMethod;
	private CacheOutputStream resultBuffer;
	private FileInputStream fileInputStream;
	private ThreadInputStream threadSystemIn;

	public ProblemItemCallable(Method mainMethod, String inputFilePath,
			CacheOutputStream resultBuffer, ThreadInputStream threadSystemIn) {
		this.mainMethod = mainMethod;
		this.resultBuffer = resultBuffer;
		this.threadSystemIn = threadSystemIn;
		// 重定向输入流,注意路径不能包含中文名
		File file = new File(inputFilePath);
		if (!file.exists()) {
			throw new RuntimeException("测试数据有问题");
		}
		try {
			fileInputStream = new FileInputStream(file);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	public ProblemResultItem call() throws Exception {
		ProblemResultItem item = new ProblemResultItem();

		try {
			threadSystemIn.setThreadIn(fileInputStream);
			mainMethod.invoke(null, new Object[] { new String[0] });

			item.setResult(new String(resultBuffer.removeBytes(Thread
					.currentThread().getId())));
			item.setNormal(true);

		} catch (InvocationTargetException e) {
			Throwable throwable = e.getTargetException();
			if (throwable instanceof OutOfMemoryError) {
				item.setMessage("内存溢出");
			} else {
				item.setMessage(throwable.getMessage());
			}
			item.setNormal(false);
		} catch (RuntimeException runtimeException) {
			item.setMessage(runtimeException.getMessage());
			item.setNormal(false);
		} finally {
			threadSystemIn.removeAndCloseThreadIn();
		}

		return item;
	}

	public void colseResource() {
		threadSystemIn.removeAndCloseThreadIn();
	}
}

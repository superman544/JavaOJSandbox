package cn.superman.sandbox.callable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import cn.superman.sandbox.core.systemInStream.ThreadInputStream;
import cn.superman.sandbox.core.systemOutStream.CacheOutputStream;
import cn.superman.sandbox.dto.Problem;
import cn.superman.sandbox.dto.ProblemResultItem;

public class ProblemCallable implements Callable<List<ProblemResultItem>> {
	private Method mainMethod;
	private Problem problem;
	private CacheOutputStream resultBuffer;
	private Runtime run = null;
	private CountDownLatch countDownLatch = null;
	private ThreadInputStream threadSystemIn;
	private static final ExecutorService itemGetThreadPool = Executors
			.newCachedThreadPool(new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread thread = new Thread(r);
					thread.setName("itemGetThreadPool id "
							+ System.currentTimeMillis());
					return thread;
				}
			});
	private static final ExecutorService itemExecThreadPool = Executors
			.newCachedThreadPool(new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread thread = new Thread(r);
					thread.setName("itemExecThreadPool id "
							+ System.currentTimeMillis());
					return thread;
				}
			});

	public ProblemCallable(Method mainMethod, Problem problem,
			CacheOutputStream resultBuffer, ThreadInputStream threadSystemIn) {
		this.mainMethod = mainMethod;
		this.problem = problem;
		this.resultBuffer = resultBuffer;
		this.threadSystemIn = threadSystemIn;
		run = Runtime.getRuntime();
	}

	@Override
	public List<ProblemResultItem> call() throws Exception {
		List<String> paths = problem.getInputDataFilePathList();
		final List<ProblemResultItem> resultItems = new ArrayList<ProblemResultItem>();
		countDownLatch = new CountDownLatch(paths.size());
		// 为了内存使用比较准确，先大概的执行一次回收吧
		run.gc();

		for (int i = 0; i < paths.size(); i++) {
			final String path = paths.get(i);
			itemExecThreadPool.execute(new Runnable() {
				@Override
				public void run() {
					resultItems.add(process(path));
				}
			});
		}

		// 阻塞线程，等待所有结果都计算完了，再返回
		countDownLatch.await();
		return resultItems;
	}

	private ProblemResultItem process(String inputFilePath) {
		ProblemResultItem item = null;
		ProblemItemCallable itemCallable = null;
		long beginMemory = 0;
		long beginTime = 0;
		long endTime = 0;
		long endMemory = 0;
		Future<ProblemResultItem> submit = null;

		try {
			itemCallable = new ProblemItemCallable(mainMethod, inputFilePath,
					resultBuffer, threadSystemIn);

			submit = itemGetThreadPool.submit(itemCallable);
			beginMemory = run.totalMemory() - run.freeMemory();
			beginTime = System.nanoTime();

			item = submit
					.get(problem.getTimeLimit() + 2, TimeUnit.MILLISECONDS);

			if (item == null) {
				killThread((FutureTask<ProblemResultItem>) submit);
				throw new TimeoutException();
			}

			endTime = System.nanoTime();
			endMemory = run.totalMemory() - run.freeMemory();
		} catch (Exception e) {
			// 出现了意外，先关闭资源再说（如已经打开的流等）
			itemCallable.colseResource();
			killThread((FutureTask<ProblemResultItem>) submit);
			item = new ProblemResultItem();
			item.setNormal(false);
			if (e instanceof CancellationException
					|| e instanceof TimeoutException) {
				// 超时了，会进来这里
				item.setMessage("超时");
			} else {
				item.setMessage(e.getMessage());
			}
			endTime = System.nanoTime();
			endMemory = run.totalMemory() - run.freeMemory();
		}
		// 时间为毫微秒，要先转变为微秒再变为毫秒
		item.setUseTime((endTime - beginTime) / 1000 / 1000);
		item.setUseMemory(endMemory - beginMemory);
		item.setInputFilePath(inputFilePath);
		if (item.getUseMemory() > problem.getMemoryLimit()) {
			item.setNormal(false);
			item.setMessage("超出内存限制");
		}
		// 无论怎么样，这里必须最后都要进行减一，不然将会一直阻塞线程，最终无法返回结果
		countDownLatch.countDown();
		return item;
	}

	/**
	 * 需要注意的是，这里将会调用线程stop方法，因为只有这样才能强行终止超时的线程，而又因为这里并不需要保证什么原子性以及一致性的业务要求，
	 * 所以用stop方法是没什么大问题的
	 * 
	 * @param submit
	 * @throws NoSuchFieldException
	 * @throws SecurityException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 */
	@SuppressWarnings("deprecation")
	private void killThread(FutureTask<ProblemResultItem> submit) {
		try {
			submit.cancel(true);
			// 利用反射，强行取出正在运行该任务的线程
			Field runner = submit.getClass().getDeclaredField("runner");
			runner.setAccessible(true);
			Thread execThread = (Thread) runner.get(submit);
			execThread.stop();
			submit.cancel(true);
		} catch (Exception e) {
			System.err.println(e);
		}

	}

	public Problem getProblem() {
		return problem;
	}

	public void setProblem(Problem problem) {
		this.problem = problem;
	}
}

package cn.superman.sandbox.core;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import com.google.gson.Gson;

import cn.superman.sandbox.callable.ProblemCallable;
import cn.superman.sandbox.constant.CommunicationSignal;
import cn.superman.sandbox.constant.ConstantParameter;
import cn.superman.sandbox.core.classLoader.SandboxClassLoader;
import cn.superman.sandbox.core.securityManager.SandboxSecurityManager;
import cn.superman.sandbox.core.systemInStream.ThreadInputStream;
import cn.superman.sandbox.core.systemOutStream.CacheOutputStream;
import cn.superman.sandbox.dto.Problem;
import cn.superman.sandbox.dto.ProblemResult;
import cn.superman.sandbox.dto.ProblemResultItem;
import cn.superman.sandbox.dto.Request;
import cn.superman.sandbox.dto.Response;
import cn.superman.sandbox.dto.SandBoxStatus;
import cn.superman.sandbox.dto.SandboxInitData;

public class Sandbox {
	// 每加载超过100个类后，就替换一个新的ClassLoader
	public static final int UPDATE_CLASSLOADER_GAP = 5;
	// 记录一共加载过的类数量
	private int loadClassCount = 0;
	private SandboxInitData sandboxInitData;
	private String pid = null;
	private ServerSocket serverSocket;
	private Socket communicateSocket;
	private SandboxClassLoader sandboxClassLoader;
	private Gson gson = null;
	private MemoryMXBean systemMemoryBean = null;
	private long beginStartTime = 0;
	// 表示当前进程是否在忙，如果在忙的话，就表示当前正在判题(这是当前正在的忙情况，以后可能会增加更多的情况)
	private boolean isBusy = false;
	private ProblemCallable problemCallable;
	// 用于重定向输出流，即代码输出的结果，将会输出到这个缓冲区中
	private volatile CacheOutputStream resultBuffer = new CacheOutputStream();
	private volatile ThreadInputStream systemThreadIn = new ThreadInputStream();
	// 用一个线程池去处理每个判题请求
	private ExecutorService problemThreadPool = Executors
			.newSingleThreadExecutor(new ThreadFactory() {

				@Override
				public Thread newThread(Runnable r) {
					Thread thread = new Thread(r);
					thread.setName("problemThreadPool");
					thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {

						@Override
						public void uncaughtException(Thread t, Throwable e) {
							writeResponse(null,
									CommunicationSignal.ResponseSignal.ERROR,
									null, e.getMessage());
						}
					});
					return thread;
				}
			});
	// 用一个线程池去等待每个判题请求的结果返回
	private ExecutorService problemResultThreadPool = Executors
			.newSingleThreadExecutor(new ThreadFactory() {

				@Override
				public Thread newThread(Runnable r) {
					Thread thread = new Thread(r);
					thread.setName("problemResultThreadPool");
					thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {

						@Override
						public void uncaughtException(Thread t, Throwable e) {
							writeResponse(null,
									CommunicationSignal.ResponseSignal.ERROR,
									null, e.getMessage());
						}
					});
					return thread;
				}
			});

	public static void main(String[] args) {
		new Sandbox(args);
	}

	private Sandbox(String[] args) {
		initSandbox(args);
	}

	/**
	 * 沙箱初始化函数
	 * @param args 初始化参数
	 */
	private void initSandbox(String[] args) {
		// 获取进程id，用于向外界反馈
		getPid();
		// 沙箱环境准备
		SandboxInitData sandboxInitData = prepareBuildingNeed(args[0]);
		// 打开用于与外界沟通的通道
		openServerSocketWaitToConnect(sandboxInitData.getPort());
		// 确保能与外界沟通之后，才开始准备执行class文件的环境
		buildEnvironment(sandboxInitData);
		// 等外界与沙箱，通过socket沟通上之后，就会进行业务上的沟通
		service();

	}

	/**
	 * 获取进程ID
	 */
	private void getPid() {
		String name = ManagementFactory.getRuntimeMXBean().getName();
		pid = name.split("@")[0];
	}

	/**
	 * 准备沙箱初始化必要内容
	 * @param sandboxInitJson 沙箱初始化JSON格式数据
	 * @return 沙箱初始化所需要的信息
	 */
	private SandboxInitData prepareBuildingNeed(String sandboxInitJson) {
		gson = new Gson();

		SandboxInitData sandboxInitData = gson.fromJson(sandboxInitJson,
				SandboxInitData.class);
		systemMemoryBean = ManagementFactory.getMemoryMXBean();
		this.sandboxInitData = sandboxInitData;
		return sandboxInitData;
	}

	/**
	 * 打开连接，等待建立连接
	 * @param port 监听端口
	 */
	private void openServerSocketWaitToConnect(int port) {

		try {
			serverSocket = new ServerSocket(port);
			System.out.println("sandbox" + port + "wait");
			communicateSocket = serverSocket.accept();
			System.out.println("pid:" + pid);
			// 只与外部建立一个沟通的连接
			serverSocket.close();
		} catch (IOException e) {
			System.err.println(e.getMessage());
			throw new RuntimeException("无法打开沙箱端Socket，可能是端口被占用了");
		}
	}

	/**
	 * 建立沙箱环境
	 * @param sandboxInitData 沙箱初始化信息
	 */
	private void buildEnvironment(SandboxInitData sandboxInitData) {
		sandboxClassLoader = new SandboxClassLoader(
				sandboxInitData.getClassFileRootPath());
		beginStartTime = System.currentTimeMillis();
		// 重定向输出流
		System.setOut(new PrintStream(resultBuffer));
		// 重定向输入流
		System.setIn(systemThreadIn);
	}

	/**
	 * 系统服务函数
	 */
	private void service() {
		try {
			Scanner scanner = new Scanner(communicateSocket.getInputStream());
			// 必须建立了连接和流之后，才能设置这里的权限
			System.setSecurityManager(new SandboxSecurityManager());
			String data = null;
			while (scanner.hasNext()) {
				// 每一次交流，都是一行一行的形式交流，即本次沟通内容发送完之后，发送方会在最后，加上一个"\n"，表示发送完了这条消息
				data = scanner.nextLine();
				Request request = gson.fromJson(data, Request.class);
				dispatchRequest(request);
			}
			scanner.close();
		} catch (Exception e) {
			writeResponse(null, CommunicationSignal.ResponseSignal.ERROR, null,
					e.getMessage());
		}
	}

	/**
	 * 请求分发函数
	 * @param request 请求内容
	 * @throws IOException 
	 */
	private void dispatchRequest(Request request) throws IOException {
		if (CommunicationSignal.RequestSignal.CLOSE_SANDBOX.equals(request
				.getCommand())) {
			closeSandboxService(request.getSignalId());
		} else if (CommunicationSignal.RequestSignal.SANDBOX_STATUS
				.equals(request.getCommand())) {
			feedbackSandboxStatusService(request.getSignalId());
		} else if (CommunicationSignal.RequestSignal.REQUSET_JUDGED_PROBLEM
				.equals(request.getCommand())) {
			if (loadClassCount >= UPDATE_CLASSLOADER_GAP) {
				loadClassCount = 0;
				// 重置类加载器，使得原有已经加载进内存的过期的类，可以得以释放
				sandboxClassLoader = new SandboxClassLoader(
						sandboxInitData.getClassFileRootPath());
				System.gc();
			}
			Future<List<ProblemResultItem>> processProblem = processProblem(request
					.getData());
			returnJudgedProblemResult(request.getSignalId(), processProblem);
			loadClassCount++;
		} else if (CommunicationSignal.RequestSignal.IS_BUSY.equals(request
				.getCommand())) {
			checkBusy(request.getSignalId());
		}
	}

	/**
	 * 关闭沙箱服务
	 * @param signalId 关闭信号
	 */
	private void closeSandboxService(String signalId) {
		writeResponse(signalId, CommunicationSignal.ResponseSignal.OK,
				CommunicationSignal.RequestSignal.CLOSE_SANDBOX, null);
		try {
			communicateSocket.close();
		} catch (IOException e) {
			System.err.println(e);
		}
		closeSandbox();
	}

	/**
	 * 返回沙箱状态的服务
	 * @param signalId 信号
	 */
	private void feedbackSandboxStatusService(String signalId) {
		SandBoxStatus sandBoxStatus = new SandBoxStatus();
		sandBoxStatus.setPid(pid);
		sandBoxStatus.setBeginStartTime(beginStartTime);
		sandBoxStatus.setBusy(isBusy);
		// 由堆内存和非堆内存组成
		long useMemory = systemMemoryBean.getHeapMemoryUsage().getUsed()
				+ systemMemoryBean.getNonHeapMemoryUsage().getUsed();
		sandBoxStatus.setUseMemory(useMemory);
		// 由堆内存和非堆内存组成
		long maxMemory = systemMemoryBean.getHeapMemoryUsage().getMax()
				+ systemMemoryBean.getNonHeapMemoryUsage().getMax();
		sandBoxStatus.setMaxMemory(maxMemory);
		writeResponse(signalId, CommunicationSignal.ResponseSignal.OK,
				CommunicationSignal.RequestSignal.SANDBOX_STATUS,
				gson.toJson(sandBoxStatus));

	}

	/**
	 * 进行项目处理
	 * @param problemJson 题目内容的JSON格式
	 * @return 题目处理结果
	 */
	private Future<List<ProblemResultItem>> processProblem(String problemJson) {
		Problem problem = gson.fromJson(problemJson, Problem.class);
		try {
			Class<?> mainClass = sandboxClassLoader.loadSandboxClass(problem
					.getClassFileName());
			Method mainMethod = mainClass.getMethod("main", String[].class);
			if (!Modifier.isStatic(mainMethod.getModifiers()))
				throw new Exception("main方法不是静态方法");

			mainMethod.setAccessible(true);
			problemCallable = new ProblemCallable(mainMethod, problem,
					resultBuffer, systemThreadIn);
			Future<List<ProblemResultItem>> submit = problemThreadPool
					.submit(problemCallable);
			isBusy = true;
			mainClass = null;
			return submit;
		} catch (ClassNotFoundException e) {
			writeResponse(null, CommunicationSignal.ResponseSignal.ERROR, null,
					e.getMessage());
		} catch (Exception e) {
			writeResponse(null, CommunicationSignal.ResponseSignal.ERROR, null,
					e.getMessage());
		}
		return null;
	}

	/**
	 * 检查沙箱是否正忙
	 * @param signalId 信号量
	 */
	private void checkBusy(String signalId) {
		String responseCommand = null;

		if (isBusy) {
			responseCommand = CommunicationSignal.ResponseSignal.YES;
		} else {
			responseCommand = CommunicationSignal.ResponseSignal.NO;
		}

		writeResponse(signalId, responseCommand,
				CommunicationSignal.RequestSignal.IS_BUSY, null);
	}

	/**
	 * 返回题目运行结果
	 * @param signalId 信号
	 * @param processProblem 题目运行结果
	 */
	private void returnJudgedProblemResult(final String signalId,
			final Future<List<ProblemResultItem>> processProblem) {
		problemResultThreadPool.execute(new Runnable() {
			@Override
			public void run() {
				if (processProblem != null) {
					try {
						List<ProblemResultItem> resultItems = processProblem
								.get();
						Problem problem = problemCallable.getProblem();
						ProblemResult problemResult = new ProblemResult();
						problemResult.setRunId(problem.getRunId());
						problemResult.setResultItems(resultItems);

						writeResponse(
								signalId,
								CommunicationSignal.ResponseSignal.OK,
								CommunicationSignal.RequestSignal.REQUSET_JUDGED_PROBLEM,
								gson.toJson(problemResult));
						isBusy = false;
						problemCallable = null;

						// 通知对方，主动告诉对方，自己已经空闲了，已经准备好下一次判题
						writeResponse(null,
								CommunicationSignal.ResponseSignal.IDLE, null,
								null);
					} catch (Exception e) {
						writeResponse(null,
								CommunicationSignal.ResponseSignal.ERROR, null,
								e.getMessage());
					}
				}
			}
		});
	}

	/**
	 * 发送回复
	 * @param signalId 信号
	 * @param responseCommand 回复的命令
	 * @param requestCommand 请求的命令
	 * @param data 数据
	 */
	private void writeResponse(String signalId, String responseCommand,
			String requestCommand, String data) {
		try {
			OutputStream outputStream = communicateSocket.getOutputStream();
			Response response = new Response();
			response.setSignalId(signalId);
			response.setResponseCommand(responseCommand);
			response.setRequestCommand(requestCommand);
			response.setData(data);
			outputStream
					.write((gson.toJson(response) + "\n").getBytes("UTF-8"));
		} catch (IOException e) {
			System.err.println(e.getMessage());
			throw new RuntimeException("无法对外输出数据");
		}

	}

	/**
	 * 关闭沙箱
	 */
	private void closeSandbox() {
		try {
			communicateSocket.close();
		} catch (IOException e) {
		}
		System.exit(ConstantParameter.EXIT_VALUE);
	}
}

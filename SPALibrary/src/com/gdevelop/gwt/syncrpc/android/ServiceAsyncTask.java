package com.gdevelop.gwt.syncrpc.android;

import android.content.Context;
import android.os.AsyncTask;

import com.gdevelop.gwt.syncrpc.HasProxySettings;
import com.gdevelop.gwt.syncrpc.SyncProxy;
import com.gdevelop.gwt.syncrpc.android.auth.ServiceAuthenticator;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.rpc.RemoteService;

/**
 * Helper class to make RPC's quicker and easier. Specifically, Android requires
 * that all network calls occur away from the main thread. This includes the
 * {@link SyncProxy#setBaseURL(String)} call as well as the actual service RPC.
 * Additionally, if you do any UI work based on the returned value, Android
 * requires that this work occur back on the main thread. This class solves both
 * problems. It automatically takes care of {@link SyncProxy#setBaseURL(String)}
 * and by being provided with the typical {@link AsyncCallback} directly, it
 * will return results (success or failure) to your {@link AsyncCallback} on the
 * main thread.
 *
 * To use this class, provide the specified parameters in the constructor and
 * override the {@link #serviceCall()} method. Specifically, your override
 * should follow the pattern below, where [Params] is the parameters to send to
 * the RPC. The {@link #getAsyncService()} and {@link #getCallback()} are
 * critical to the described functionality.
 *
 * <pre>
 * {@code
 * getAsyncService().rpcMethodCall([Params], getCallback());
 * }
 * </pre>
 *
 * You may also override {@link #onProgressUpdate(Object...)} in order to
 * understand where in the process the task is. The progress is defined by
 * {@link ServiceTaskProgress}.
 *
 * @author Preethum
 * @since 0.6
 * @param <AsyncService>
 *            the AsyncService class that will be utilized for the RPC
 * @param <ReturnType>
 *            the type expected to be returned from the RPC
 */
public abstract class ServiceAsyncTask<AsyncService, ReturnType> extends
AsyncTask<Context, ServiceTaskProgress, Void> {
	private AsyncCallback<ReturnType> primaryCallback;
	private ServiceAuthenticator authenticator;
	private int rpcBaseRes = -1;

	/**
	 *
	 * @param authenticator
	 *            the authenticator that will be applied to the service before
	 *            the RPC
	 */
	public <ServiceClass extends RemoteService> ServiceAsyncTask(
			Class<ServiceClass> clazz, int rpcBaseRes,
			ServiceAuthenticator authenticator,
			AsyncCallback<ReturnType> primaryCallback) {
		this(clazz, rpcBaseRes, primaryCallback);
		this.authenticator = authenticator;
	}

	/**
	 *
	 * @param clazz
	 *            service class definition
	 * @param rpcBaseRes
	 *            the resource string that specifies the Server's Base URL, for
	 *            use with {@link SyncProxy#setBaseURL(String)}
	 * @param primaryCallback
	 *            the callback that will handle the success or failure returned
	 *            by the RPC
	 */
	@SuppressWarnings("unchecked")
	public <ServiceClass extends RemoteService> ServiceAsyncTask(
			Class<ServiceClass> clazz, int rpcBaseRes,
			AsyncCallback<ReturnType> primaryCallback) {
		this.serviceClass = (Class<RemoteService>) clazz;
		this.rpcBaseRes = rpcBaseRes;
		this.primaryCallback = primaryCallback;
	}

	private Class<RemoteService> serviceClass;
	private AsyncService asyncService;
	private BridgeCallback<ReturnType> callback;

	/**
	 * May be overridden if you need to customize the creation of the service
	 */
	protected AsyncService getAsyncService() {
		if (asyncService == null) {
			asyncService = SyncProxy.create(serviceClass);
		}
		return asyncService;
	}

	/**
	 * Overrides the Default timeout of the intermediary {@link AsyncCallback},
	 * specified as {@link BridgeCallback#DEFAULT_TIMEOUT}.
	 */
	public void setTimeout(int seconds) {
		((BridgeCallback<ReturnType>) getCallback()).setTimeout(seconds);
	}

	protected AsyncCallback<ReturnType> getCallback() {
		if (callback == null) {
			callback = new BridgeCallback<ReturnType>();
		}
		return callback;
	}

	/**
	 * Performs the following functions:
	 * <ol>
	 * <li>Calls {@link SyncProxy#setBaseURL(String)}</li>
	 * <li>Creates the AsyncService (assuming first call to
	 * {@link #getAsyncService()})</li>
	 * <li>Applies the {@link ServiceAuthenticator} if one was provided
	 * <li>
	 * <li>Calls {@link #serviceCall()} in order to perform the RPC</li>
	 * </ol>
	 */
	@Override
	protected Void doInBackground(Context... params) {
		// Activates GSP Logging on Android
		// for (Class<?> clazz : SyncProxy.getLoggerClasses()) {
		// FixedAndroidHandler.setupLogger(Logger.getLogger(clazz.getName()));
		// }
		// SyncProxy.setLoggingLevel(Level.FINE);
		SyncProxy.setBaseURL(params[0].getString(rpcBaseRes));
		publishProgress(ServiceTaskProgress.BASE_SET);
		// Initiate creation of the service
		getAsyncService();
		publishProgress(ServiceTaskProgress.SERVICE_CREATED);
		if (authenticator != null) {
			authenticator
			.applyAuthenticationToService((HasProxySettings) asyncService);
			publishProgress(ServiceTaskProgress.AUTH_APPLIED);
		}
		serviceCall();
		publishProgress(ServiceTaskProgress.SERVICE_CALLED);
		// Wait for the service call to complete before passing results back to
		// primaryCallback in onPostExecute
		try {
			((BridgeCallback<ReturnType>) getCallback()).await();
			publishProgress(ServiceTaskProgress.RPC_COMPLETE);
		} catch (InterruptedException e) {
			publishProgress(ServiceTaskProgress.RPC_INTERRUPTED);
			throw new RuntimeException(e);
		}
		return null;
	}

	/**
	 * Returns results or exceptions from RPC back to provided
	 * {@link AsyncCallback} on the app's main thread
	 */
	@Override
	protected void onPostExecute(Void result) {
		if (callback.wasSuccessful()) {
			primaryCallback.onSuccess(callback.getResult());
		} else {
			// callback.getCaught().printStackTrace();
			primaryCallback.onFailure(callback.getCaught());
		}
	}

	/**
	 * Contains the service RPC to call. Typically this should have the form:
	 *
	 * <pre>
	 * {@code
	 * getAsyncService().rpcMethodCall([Params], getCallback());
	 * }
	 * </pre>
	 */
	public abstract void serviceCall();

}

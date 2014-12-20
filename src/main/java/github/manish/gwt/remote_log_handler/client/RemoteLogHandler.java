/*
* Copyright 2014 Manish Ahluwalia
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package github.manish.gwt.remote_log_handler.client;

import github.manish.gwt.remote_log_handler.shared.RemoteLoggingService;
import github.manish.gwt.remote_log_handler.shared.RemoteLoggingServiceAsync;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import com.google.gwt.logging.client.RemoteLogHandlerBase;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.Window.ClosingHandler;
import com.google.gwt.user.client.Window.Location;
import com.google.gwt.user.client.rpc.AsyncCallback;

/**
 * A GWT {@link RemoteLogHandlerBase} that does the following:
 * <ul>
 * <li>Buffer logs on client side and sends them to the server only after one of
 * the following conditions is met:
 * <ol>
 * <li>A log event with a specific threshold is encountered (Default: WARNING)</li>
 * <li>A certain amount of time has elapsed since the buffer was last flushed
 * (Default: 10s)</li>
 * <li>A certain number of log events have been buffered (Default: 50)</li>
 * <li>The user closes the window or navigates to a different site</li>
 * </ol>
 * </li>
 * <li>Stores a context and sends the context over to the server along with the
 * buffer of log events. Think of this like MDC on the client side.</li>
 * <li>Sets the default github.manish.gwt.remote_log_handler threshold based on the url parameter
 * <code>clientLogging</code></li>
 * </ul>
 * <p>
 * To use this, implement {@link RemoteLoggingService} on the server side. On
 * the client side call {@link #initialize()} and optionallay,
 * {@link #getContext()} to get the context map to fill out.
 * </p>
 */
public class RemoteLogHandler extends RemoteLogHandlerBase
{
    class DefaultCallback implements AsyncCallback<String>
    {
        public void onFailure (Throwable caught)
        {
            wireLogger.log(Level.SEVERE, "Remote github.manish.gwt.remote_log_handler failed: ", caught);
        }

        public void onSuccess (String result)
        {
            if (result != null)
            {
                wireLogger.severe("Remote github.manish.gwt.remote_log_handler failed: " + result);
            }
            else
            {
                wireLogger.finest("Remote github.manish.gwt.remote_log_handler message acknowledged");
            }
        }
    }

    /* XXX The following should be read from properties, and validated here */

    /** The number of ms to buffer the logs before sending them to the server */
    private static final int PERIOD_TO_UPLOAD_LOGS = 10000; // in ms
    private static final Level FLUSH_LOG_LEVEL_THRESHOLD = Level.WARNING;
    private static final int BUFFER_SIZE_THRESHOLD = 50;

    private static RemoteLogHandler singleton = null;

    private AsyncCallback<String> callback;
    private RemoteLoggingServiceAsync service;
    private LinkedList<LogRecord> logBuffer;
    private final HashMap<String, String> context = new HashMap<String, String>();
    private boolean contextInitialized = false;
    private boolean noBuffering = !GWT.isProdMode(); // Never buffer in dev mode. Buffer as long as possible in Prod mode

    public RemoteLogHandler ()
    {
        if (null != singleton)
        {
            throw new RuntimeException("RemoteLogHandler needs to be a singleton");
        }

        this.service = (RemoteLoggingServiceAsync)GWT.create(RemoteLoggingService.class);
        this.callback = new DefaultCallback();
        this.logBuffer = new LinkedList<LogRecord>();

        Scheduler.get().scheduleFixedDelay(new RepeatingCommand() {
            //@Override
            public boolean execute ()
            {
                flushLogs();
                return true;
            }
        }, PERIOD_TO_UPLOAD_LOGS);

        singleton = this;
    }

    public static RemoteLogHandler get ()
    {
        if (null == singleton)
        {
            singleton = new RemoteLogHandler();
        }
        return singleton;
    }

    public void flushLogs ()
    {
        if (!logBuffer.isEmpty() && contextInitialized)
        {
            LinkedList<LogRecord> buffer = logBuffer;
            logBuffer = new LinkedList<LogRecord>();
            service.sendLogs(context, buffer, callback);
        }
    }

    @Override
    public void publish (LogRecord record)
    {
        if (isLoggable(record))
        {
            logBuffer.addLast(record);

            if (noBuffering 
                    || record.getLevel().intValue() >= FLUSH_LOG_LEVEL_THRESHOLD.intValue()
                    || logBuffer.size() >= BUFFER_SIZE_THRESHOLD)
            {
                flushLogs();
            }
        }
    }

    public HashMap<String, String> getContext ()
    {
        return context;
    }

    public void contextInitalized ()
    {
        contextInitialized = true;
    }
    
    public void initialize ()
    {
        Window.addWindowClosingHandler(new ClosingHandler() {
            //@Override
            public void onWindowClosing (ClosingEvent e)
            {
                // TODO: What happens if context is not initialized?
                noBuffering = true;
                flushLogs();
            }
        });

        String clientLogging = Location.getParameter("clientLogging");
        if (null == clientLogging)
        {
            return;
        }

        try
        {
            Level paramLevel = Level.parse(clientLogging);
            if (null != paramLevel)
            {
                GWT.log("Setting client github.manish.gwt.remote_log_handler level to " + clientLogging
                        + ", translated to " + paramLevel);
                Logger.getLogger("").setLevel(paramLevel);
            }
            else
            {
                GWT.log("Null parsed param level for clientLogging: " + clientLogging);
            }
        }
        catch (Exception e)
        {
            GWT.log("Setting client github.manish.gwt.remote_log_handler failed for string: " + clientLogging, e);
        }
    }
}

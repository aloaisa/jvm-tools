/**
 * Copyright 2014 Alexey Ragozin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gridkit.jvmtool.cmd;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.gridkit.jvmtool.JmxConnectionInfo;
import org.gridkit.jvmtool.SJK;
import org.gridkit.jvmtool.SJK.CmdRef;
import org.gridkit.jvmtool.TimeIntervalConverter;
import org.gridkit.jvmtool.stacktrace.StackTraceCodec;
import org.gridkit.jvmtool.stacktrace.StackTraceWriter;
import org.gridkit.jvmtool.stacktrace.ThreadDumpSampler;
import org.gridkit.jvmtool.stacktrace.ThreadSnapshot;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;

/**
 * Stack capture command.
 *  
 * @author Alexey Ragozin (alexey.ragozin@gmail.com)
 */
public class StackCaptureCmd implements CmdRef {

	@Override
	public String getCommandName() {
		return "stcap";
	}

	@Override
	public Runnable newCommand(SJK host) {
		return new StCap(host);
	}

	@Parameters(commandDescription = "[Stack Capture] Dumps stack traces to file for further processing")
	public static class StCap implements Runnable {

	  private static final ObjectName THREADING_MBEAN = name("java.lang:type=Threading");
	  private static ObjectName name(String name) {
	      try {
	          return new ObjectName(name);
	      } catch (MalformedObjectNameException e) {
	          throw new RuntimeException(e);
	      }
	  }


		@ParametersDelegate
		private SJK host;
		
		@Parameter(names = {"-i", "--sampler-interval"}, converter = TimeIntervalConverter.class, description = "Interval between polling MBeans")
		private long samplerIntervalMS = 0;
		
		@Parameter(names = {"-f", "--filter"}, description = "Wild card expression to filter thread by name")
		private String threadFilter = ".*";

		@Parameter(names = {"-m", "--match-frame"}, variableArity = true, description = "Frame filter, only trace conatining this string would be included to dump")
		private List<String> frameFilter;

	    @Parameter(names = {"-o", "--output"}, required = true, description = "Name of file to write thread dump")
	    private String outputFile;

	    @Parameter(names = {"-l", "--limit"}, description = "Target number of traces to collect, once reached command will terminate (0 - unlimited)")
	    private long limit = 0;

        @Parameter(names = {"-t", "--timeout"}, converter = TimeIntervalConverter.class, description = "Time untill command will terminate even if not enough traces collected")
        private long timeoutMS = TimeUnit.SECONDS.toMillis(30);

        @Parameter(names = {"-r", "--rotate"}, description = "If specified output file would be rotate every N traces (0 - do not rotate)")
        private long fileLimit = 0;

		
		@ParametersDelegate
		private JmxConnectionInfo connInfo = new JmxConnectionInfo();

		private ThreadDumpSampler sampler;
		private long traceCounter = 0;
		private long lastRotate = 0;
		private int rotSeg = 0;

        private StackTraceWriter writer;
		
		public StCap(SJK host) {
			this.host = host;
		}
		
		@Override
		public void run() {
			
			try {
				MBeanServerConnection mserver = connInfo.getMServer();
				ThreadMXBean bean = connectThreadMXBean(mserver);

				sampler = new ThreadDumpSampler();
				sampler.setThreadFilter(threadFilter);
				if (frameFilter != null) {
    				for(String f: frameFilter) {
				    SJK.fail("Frame filter is not implemented yet");
//    				    sampler.addFrame(f);
    				}
				}
				
				sampler.connect(bean);

				if (limit == 0) {
				    limit = Long.MAX_VALUE;
				}
				
				StackTraceWriter proxy = new StackWriterProxy();
				
				openWriter();
				long deadline = System.currentTimeMillis() + timeoutMS;
				long nextReport = 500;
				while(System.currentTimeMillis() < deadline && traceCounter < limit) {
				    long nextsample = System.currentTimeMillis() + samplerIntervalMS;
				    sampler.collect(proxy);
				    if (traceCounter >= nextReport) {
				        System.out.println("Collected " +traceCounter);
				        while(traceCounter >= nextReport) {
				            nextReport += 500;
				        }
				        checkRotate();
				    }				    
				    // delay
				    while(nextsample > System.currentTimeMillis()) {
				        long st = nextsample - System.currentTimeMillis();
				        if (st > 0) {
				            Thread.sleep(st);
				        }
				    }
				}

				writer.close();
				System.out.println("Trace dumped: " + traceCounter);
				
			} catch (Exception e) {
				SJK.fail("Unexpected error: " + e.toString(), e);
			}			
		}

        @SuppressWarnings("restriction")
        protected ThreadMXBean connectThreadMXBean(MBeanServerConnection mserver) {
            ThreadMXBean bean;
            try {
                bean = JMX.newMXBeanProxy(mserver, THREADING_MBEAN, com.sun.management.ThreadMXBean.class);
            }
            catch(Exception e) {
                bean = JMX.newMXBeanProxy(mserver, THREADING_MBEAN, ThreadMXBean.class);
            }
            return bean;
        }

        private void checkRotate() throws FileNotFoundException, IOException {
            if (fileLimit > 0) {
                if (traceCounter - lastRotate > fileLimit) {
                    writer.close();
                    ++rotSeg;
                    lastRotate = traceCounter;
                    openWriter();
                }
            }
        }

        private class StackWriterProxy implements StackTraceWriter {

            @Override
            public void write(ThreadSnapshot snap) throws IOException {
                ++traceCounter;
                writer.write(snap);

            }

            @Override
            public void close() {
                writer.close();
            }
        }

        private void openWriter() throws FileNotFoundException, IOException {
            if (fileLimit < 1) {
                File file = new File(outputFile);
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                writer = StackTraceCodec.newWriter(new FileOutputStream(file));
                System.out.println("Writing to " + file.getAbsolutePath());
            }
            else {
                int c = outputFile.lastIndexOf('.');
                String pref = c < 0 ? outputFile : outputFile.substring(0, c);
                String suf = c < 0 ? "" : outputFile.substring(c);
                String name = pref + String.format("-%02d", rotSeg) + suf;
                File file = new File(name);
                if (file.getParentFile() != null) {
                    file.getParentFile().mkdirs();
                }
                writer = StackTraceCodec.newWriter(new FileOutputStream(file));
                System.out.println("Writing to " + file.getAbsolutePath());
            }
        }
	}
}
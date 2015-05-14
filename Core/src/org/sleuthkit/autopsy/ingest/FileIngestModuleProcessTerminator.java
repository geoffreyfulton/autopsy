/*
 * Autopsy Forensic Browser
 *
 * Copyright 2014 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
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
package org.sleuthkit.autopsy.ingest;

import org.sleuthkit.autopsy.coreutils.ExecUtil;
import org.sleuthkit.autopsy.coreutils.ExecUtil.ProcessTerminator;

/**
 * A process terminator for file ingest modules that checks for ingest job
 * cancellation and optionally checks for process run time in excess of a
 * specified maximum.
 */
public final class FileIngestModuleProcessTerminator implements ProcessTerminator {

    private final IngestJobContext context;
    private ExecUtil.TimedProcessTerminator timedTerminator;
    private ExecUtil.ProcTerminationCode terminationCode;

    /**
     * Constructs a process terminator for a file ingest module.
     *
     * @param context The ingest job context for the ingest module.
     */
    public FileIngestModuleProcessTerminator(IngestJobContext context) {
        this.context = context;
        this.terminationCode = ExecUtil.ProcTerminationCode.NONE;
    }

    /**
     * Constructs a process terminator for a file ingest module.
     *
     * @param context The ingest job context for the ingest module.
     * @param maxRunTimeInSeconds Maximum allowable run time of process.
     */
    public FileIngestModuleProcessTerminator(IngestJobContext context, long maxRunTimeInSeconds) {
        this(context);
        this.timedTerminator = new ExecUtil.TimedProcessTerminator(maxRunTimeInSeconds);
    }
    
    
    /**
     * Constructs a process terminator for a file ingest module. Adds ability to 
     * use global process termination time out.
     *
     * @param context The ingest job context for the ingest module.
     * @param useGlobalTimeOut Flag whether to use global process termination timeout.
     */
    public FileIngestModuleProcessTerminator(IngestJobContext context, boolean useGlobalTimeOut) {
        this(context);
        if (useGlobalTimeOut) {
            this.timedTerminator = new ExecUtil.TimedProcessTerminator();
        }
    }    

    /**
     * @inheritDoc
     */
    @Override
    public boolean shouldTerminateProcess() {

        if (this.context.fileIngestIsCancelled()) {
            this.terminationCode = ExecUtil.ProcTerminationCode.CANCELATION;
            return true;
        }

        if (this.timedTerminator != null && this.timedTerminator.shouldTerminateProcess()) {
            this.terminationCode = ExecUtil.ProcTerminationCode.TIME_OUT;
            return true;            
        }
        
        return false;
    }

    /**
     * Returns process termination code.
     * @return ExecUtil.ProcTerminationCode Process termination code.
     */
    public ExecUtil.ProcTerminationCode getTerminationCode(){
        return this.terminationCode;
    }
}

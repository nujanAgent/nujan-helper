package com.nujan.helper.runtime

import com.nujan.helper.core.runtime.JobLauncher
import com.nujan.helper.core.runtime.JobProcess
import com.nujan.helper.core.runtime.JobSpec
import com.nujan.helper.core.runtime.PtyLaunch
import java.io.InputStream

/**
 * The `:app` leg of the JobEngine: turns a [JobSpec] into a real proot supervisor on a
 * PTY (via [PtyManager]) and adapts the [com.nujan.helper.core.runtime.PtySession] down to the
 * narrower [JobProcess] the engine drives. One PTY child per job.
 */
class PtyJobLauncher(private val pty: PtyManager) : JobLauncher {
    override fun launch(spec: JobSpec): JobProcess {
        val session = pty.start(PtyLaunch.job(pty.layout, spec.command, spec.workspaceHostPath))
        return object : JobProcess {
            override val output: InputStream = session.output
            override fun write(bytes: ByteArray) {
                session.input.write(bytes)
                session.input.flush()
            }
            override fun waitFor(): Int = session.waitFor()
            override fun signal(sig: Int) = session.signal(sig)
            override fun close() = session.close()
        }
    }
}

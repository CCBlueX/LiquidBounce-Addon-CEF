package net.ccbluex.liquidbounce.cef.download

import net.ccbluex.liquidbounce.integration.task.type.Task
import net.ccbluex.liquidbounce.cef.listeners.CefNativesProgressListener

class CefNativesProgressForwarder(val task: Task) : CefNativesProgressListener {

    /**
     * Progress update for general tasks
     *
     * @param task Task name
     * @param progress Progress
     */
    @Suppress("EmptyFunctionBlock")
    override fun onProgressUpdate(task: String, progress: Float) {}

    /**
     * If everything is complete
     */
    @Suppress("EmptyFunctionBlock")
    override fun onComplete() {}

    /**
     * File download or extraction start
     * @param taskName Task name
     */
    override fun onFileStart(taskName: String) {
        task.getOrCreateFileTask(taskName)
    }

    /**
     * File download or extraction progress
     * @param taskName Task name
     * @param bytesRead Bytes read
     * @param contentLength Total bytes
     * @param done Is download or extraction done
     */
    override fun onFileProgress(taskName: String, bytesRead: Long, contentLength: Long, done: Boolean) {
        task.getOrCreateFileTask(taskName).update(bytesRead, contentLength)
    }

    /**
     * File download or extraction end
     * @param taskName Task name
     */
    override fun onFileEnd(taskName: String) {
        task.getOrCreateFileTask(taskName).isCompleted = true
    }

}

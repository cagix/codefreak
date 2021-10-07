package org.codefreak.codefreak.service

import com.spotify.docker.client.DockerClient.ListContainersParam
import com.spotify.docker.client.messages.Container
import com.spotify.docker.client.messages.ContainerInfo
import com.spotify.docker.client.messages.HostConfig
import java.io.IOException
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.util.EntityUtils
import org.codefreak.codefreak.config.AppConfiguration
import org.codefreak.codefreak.entity.Answer
import org.codefreak.codefreak.entity.Task
import org.codefreak.codefreak.repository.AnswerRepository
import org.codefreak.codefreak.repository.TaskRepository
import org.codefreak.codefreak.service.file.FileService
import org.codefreak.codefreak.service.workspace.WorkspaceIdentifier
import org.codefreak.codefreak.service.workspace.WorkspacePurpose
import org.codefreak.codefreak.service.workspace.WorkspaceService
import org.codefreak.codefreak.util.DockerUtil
import org.codefreak.codefreak.util.NetUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StreamUtils

@Service
class IdeService : BaseService() {
  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
    const val LABEL_READ_ONLY_ANSWER_ID = ContainerService.LABEL_PREFIX + "answer-id-read-only"
    const val LABEL_ANSWER_ID = ContainerService.LABEL_PREFIX + "answer-id"
    const val LABEL_TASK_ID = ContainerService.LABEL_PREFIX + "task-id"

    // directory inside the IDE container with project files
    const val PROJECT_PATH = "/home/coder/project"
  }

  @Autowired
  private lateinit var ideService: IdeService

  @Autowired
  private lateinit var containerService: ContainerService

  @Autowired
  private lateinit var taskRepository: TaskRepository

  @Autowired
  private lateinit var answerRepository: AnswerRepository

  @Autowired
  private lateinit var fileService: FileService

  @Autowired
  private lateinit var reverseProxy: ReverseProxy

  @Autowired
  lateinit var config: AppConfiguration

  @Autowired
  lateinit var httpClient: HttpClient

  @Autowired
  private lateinit var workspaceService: WorkspaceService

  private var idleContainers: Map<String, Long> = mapOf()

  /**
   * Start an IDE container for the given submission and returns the container ID
   * If there is already a container for the submission it will be used instead
   */
  @Throws(ResourceLimitException::class)
  fun startIdeContainer(answer: Answer, readOnly: Boolean = false): String {
    val label = if (readOnly) LABEL_READ_ONLY_ANSWER_ID else LABEL_ANSWER_ID
    val configureContainer: ContainerConfigurator = {
      containerConfig {
        // apply custom cmd if present
        answer.task.ideArguments?.let { args ->
          cmd(*DockerUtil.splitCommand(args))
        }
      }
      hostConfig {
        // mount docker daemon if image requires it
        if (shouldMountDockerDaemon(answer.task.ideImage)) {
          log.debug("Mounting Docker daemon to IDE for answer ${answer.id}")
          appendBinds(
              HostConfig.Bind.builder()
                  .from("/var/run/docker.sock")
                  .to("/var/run/docker.sock")
                  .build()
          )
        }
      }
    }
    return startIdeContainer(answer.id, label, answer.id, answer.task.ideImage, configureContainer)
  }

  private fun shouldMountDockerDaemon(customImage: String?): Boolean {
    val image = customImage ?: config.ide.image
    return config.ide.dockerDaemonAllowlist.contains(
        DockerUtil.getImageNameWithoutTag(image)
    )
  }

  @Throws(ResourceLimitException::class)
  fun startIdeContainer(task: Task): String {
    return startIdeContainer(task.id, LABEL_TASK_ID, task.id)
  }

  @Synchronized
  @Throws(ResourceLimitException::class)
  fun startIdeContainer(id: UUID, label: String, fileCollectionId: UUID, customImage: String? = null, customize: ContainerConfigurator = {}): String {
    // either take existing container or create a new one
    val container = containerService.getContainerWithLabel(label, id.toString())
    if (container != null && containerService.isContainerRunning(container.id())) {
      return getIdeUrl(container.id())
    }

    if (!canStartNewIdeContainer()) {
      throw ResourceLimitException("Cannot start new IDE. Maximum capacity reached.")
    }

    // lock early so the container files will not be altered before it is ready
    return containerService.withCollectionFileLock(id) {
      if (container == null) {
        log.info("Creating new IDE container with $label=$id")
        val containerId = this.createIdeContainer(label, id, customImage, customize)
        containerService.startContainer(containerId)
        // prepare the environment after the container has started
        this.copyFilesToIde(containerId, fileCollectionId)
        getIdeUrl(containerId)
      } else {
        // make sure the container is running. Also existing ones could have been stopped
        containerService.startContainer(container.id())
        // write fresh files that might have been uploaded while being stopped
        this.copyFilesToIde(container.id(), fileCollectionId)
        getIdeUrl(container.id())
      }
    }
  }

  fun checkIdeLiveliness(task: Task): Boolean {
    val container = containerService.getContainerWithLabel(LABEL_TASK_ID, task.id.toString()) ?: return false
    return checkIdeLiveliness(container.id())
  }

  fun checkIdeLiveliness(answer: Answer, readOnly: Boolean): Boolean {
    val containerId = getAnswerIdeContainer(answer.id, readOnly) ?: return false
    return checkIdeLiveliness(containerId)
  }

  /**
   * Check if IDEs HTTP server is reachable and returns an okayish status code
   */
  private fun checkIdeLiveliness(containerId: String): Boolean {
    val ideUrl = getIdeUrl(containerId)
    val request = RequestBuilder.get(ideUrl).build()
    return try {
      val response = httpClient.execute(request)
      // make sure the connection is released even if we do not use the response content
      EntityUtils.consumeQuietly(response.entity)
      response.statusLine.statusCode < 400
    } catch (e: IOException) {
      false
    }
  }

  fun canStartNewIdeContainer(): Boolean {
    if (config.ide.maxContainers < 0) return true
    return getAllIdeContainers(ListContainersParam.withStatusRunning()).size < config.ide.maxContainers
  }

  /**
   * Get the URL for an IDE container
   */
  fun getIdeUrl(containerId: String): String {
    return this.reverseProxy.getIdeUrl(
        containerService.inspectContainer(containerId)
    )
  }

  fun isIdeContainerRunning(answerId: UUID): Boolean {
    return getAnswerIdeContainer(answerId)?.let { containerService.isContainerRunning(it) } ?: false
  }

  protected fun getAnswerIdeContainer(answerId: UUID, readOnly: Boolean = false): String? {
    val label = if (readOnly) LABEL_READ_ONLY_ANSWER_ID else LABEL_ANSWER_ID
    return containerService.getContainerWithLabel(label, answerId.toString())?.id()
  }

  private fun buildAnswerIdeWorkspaceIdentifier(answerId: UUID): WorkspaceIdentifier {
    return WorkspaceIdentifier(WorkspacePurpose.ANSWER_IDE, answerId.toString())
  }

  @Transactional
  fun saveAnswerFiles(answer: Answer, force: Boolean = false): Answer {
    if (!force && !answer.isEditable) {
      log.info("Skipped saving of files from answer ${answer.id} because it's not editable anymore")
      return answer
    }
    workspaceService.saveWorkspaceFiles(buildAnswerIdeWorkspaceIdentifier(answer.id))

    val containerId = getAnswerIdeContainer(answer.id)
    if (containerId === null) {
      log.debug("Not saving files of answer ${answer.id}: No write container found")
      return answer
    }
    if (!containerService.isContainerRunning(containerId)) {
      log.debug("Skipped saving of files from answer ${answer.id} because IDE is not running")
      return answer
    }
    containerService.withCollectionFileLock(answer.id) {
      containerService.archiveContainer(containerId, "$PROJECT_PATH/.") { tar ->
        fileService.writeCollectionTar(answer.id).use { StreamUtils.copy(tar, it) }
      }
    }
    log.info("Saved files of answer ${answer.id} from container $containerId (force=$force)")
    answer.updatedAt = Instant.now()
    return entityManager.merge(answer)
  }

  @Transactional
  fun saveTaskFiles(task: Task): Task {
    val containerId = containerService.getContainerWithLabel(LABEL_TASK_ID, task.id.toString())?.id() ?: return task
    containerService.archiveContainer(containerId, "$PROJECT_PATH/.") { tar ->
      fileService.writeCollectionTar(task.id).use { StreamUtils.copy(tar, it) }
    }
    log.info("Saved files of task ${task.id} from container $containerId")
    return entityManager.merge(task)
  }

  /**
   * Configure and create a new IDE container.
   * Returns the ID of the created container
   */
  protected fun createIdeContainer(label: String, id: UUID, customImage: String? = null, customize: ContainerConfigurator = {}): String {
    val image = customImage ?: config.ide.image
    val containerId = containerService.createContainer(image) {
      labels = mapOf(
          label to id.toString()
      )
      reverseProxy.configureContainer(this)
      hostConfig {
        restartPolicy(HostConfig.RestartPolicy.unlessStopped())
        capAdd("SYS_PTRACE") // required for lsof
        memory(config.ide.memory)
        memorySwap(config.ide.memory) // memory+swap = memory ==> 0 swap
        nanoCpus(config.ide.cpus * 1000000000L)
      }
      customize()
    }

    // attach to network
    containerService.connectToNetwork(containerId, config.ide.network)

    return containerId
  }

  /**
   * Write a file collection to an IDE container. This can only be called
   * on a running container and will throw an IllegalStateException otherwise.
   * Please lock the collection before using this method to prevent losing files
   */
  protected fun copyFilesToIde(containerId: String, fileCollectionId: UUID) {
    // extract possible existing files of the current submission into project dir
    if (fileService.collectionExists(fileCollectionId)) {
      // remove existing files before writing new ones and create the project directory (if it does not exist)
      // use sh to make globbing work
      // two globs: one for regular files and one for hidden files/dirs except . and ..
      containerService.exec(
          containerId,
          arrayOf("sh", "-c", "rm -rf $PROJECT_PATH/* $PROJECT_PATH/.[!.]* && mkdir -p $PROJECT_PATH")
      )

      fileService.readCollectionTar(fileCollectionId).use {
        containerService.copyToContainer(it, containerId, PROJECT_PATH)
      }
    }

    // change owner from root to coder so we can edit our project files
    containerService.exec(containerId, arrayOf("chown", "-R", "coder:coder", PROJECT_PATH))
  }

  fun answerFilesUpdatedExternally(answerId: UUID) {
    try {
      containerService.withCollectionFileLock(answerId) {
        getAnswerIdeContainer(answerId)?.let { copyFilesToIde(it, answerId) }
      }
    } catch (e: IllegalStateException) {
      // happens if the IDE is not running.
      // We could check if it is running before but the container might get killed while we update files
      log.debug("Not updating files in IDE for answer $answerId: ${e.message}")
    }
  }

  protected fun getAllIdeContainers(
    vararg listParams: ListContainersParam
  ) = mutableListOf<Container>().apply {
    addAll(containerService.listContainers(ListContainersParam.withLabel(LABEL_ANSWER_ID), *listParams))
    addAll(containerService.listContainers(ListContainersParam.withLabel(LABEL_READ_ONLY_ANSWER_ID), *listParams))
    addAll(containerService.listContainers(ListContainersParam.withLabel(LABEL_TASK_ID), *listParams))
  }

  /**
   * Remove answer container and possible read-only containers
   */
  fun removeAnswerIdeContainers(answerId: UUID) {
    workspaceService.deleteWorkspace(buildAnswerIdeWorkspaceIdentifier(answerId))
    arrayOf(
        getAnswerIdeContainer(answerId),
        getAnswerIdeContainer(answerId, readOnly = true)
    ).filterNotNull().forEach {
      stopIdeContainer(it)
      removeIdeContainer(it)
    }
  }

  fun stopIdeContainer(containerId: String) {
    val labels = containerService.inspectContainer(containerId).config().labels() ?: mapOf()
    when {
      labels.containsKey(LABEL_READ_ONLY_ANSWER_ID)
      -> log.info("Shutting down read container $containerId for answer ${labels[LABEL_READ_ONLY_ANSWER_ID]}")
      labels.containsKey(LABEL_ANSWER_ID) -> {
        val answer = answerRepository.findById(UUID.fromString(labels[LABEL_ANSWER_ID]))
        if (answer.isPresent) {
          ideService.saveAnswerFiles(answer.get())
        } else {
          log.warn("Answer ${labels[LABEL_ANSWER_ID]} not found. Files are not saved!")
        }
        log.info("Shutting down container $containerId of answer ${labels[LABEL_ANSWER_ID]}")
      }
      labels.containsKey(LABEL_TASK_ID) -> {
        val task = taskRepository.findById(UUID.fromString(labels[LABEL_TASK_ID]))
        if (task.isPresent) {
          ideService.saveTaskFiles(task.get())
        } else {
          log.warn("Task ${labels[LABEL_TASK_ID]} not found. Files are not saved!")
        }
        log.info("Shutting down container $containerId of task ${labels[LABEL_TASK_ID]}")
      }
      else -> {
        log.info("Container $containerId does not seem like an IDE container.")
        return
      }
    }
    containerService.stopContainer(containerId, 2)
  }

  fun removeIdeContainer(containerId: String, condition: ((info: ContainerInfo) -> Boolean)? = null) {
    val inspection = containerService.inspectContainer(containerId)
    val labels = inspection.config().labels() ?: mapOf()
    if (condition == null || condition(inspection)) {
      when {
        labels.containsKey(LABEL_ANSWER_ID) -> log.info("Removing IDE of answer ${labels[LABEL_ANSWER_ID]}")
        labels.containsKey(LABEL_READ_ONLY_ANSWER_ID) -> log.info("Removing read-only IDE of answer ${labels[LABEL_READ_ONLY_ANSWER_ID]}")
        else -> {
          log.info("Container $containerId does not seem like an IDE container.")
          return
        }
      }
      containerService.removeContainer(containerId, force = true, removeVolumes = true)
    }
  }

  @Scheduled(
      fixedRateString = "\${codefreak.ide.idle-check-rate}",
      initialDelayString = "\${codefreak.ide.idle-check-rate}"
  )
  protected fun shutdownIdleIdeContainers() {
    log.debug("Checking for idle containers")
    // create a new map to not leak memory if containers disappear in another way
    val newIdleContainers: MutableMap<String, Long> = mutableMapOf()
    getAllIdeContainers(ListContainersParam.withStatusRunning()).forEach { container ->
      val containerId = container.id()
      val tcpConnections = containerService.exec(containerId, arrayOf("cat", "/proc/net/tcp")).output.trim()
      // check if there is an established TCP connection on port 3000
      val isClientConnected = NetUtil.tcpConnectionSequence(tcpConnections).any {
        it.localPort == 3000L && it.connectionState == NetUtil.ConnectionState.TCP_ESTABLISHED
      }
      if (!isClientConnected) {
        val now: Long = System.currentTimeMillis()
        val idleSince: Long = idleContainers[containerId] ?: now
        val idleFor = now - idleSince
        log.debug("Container $containerId has been idle for more than $idleFor ms")
        if (idleFor >= config.ide.idleShutdownThreshold) {
          stopIdeContainer(containerId)
        } else {
          newIdleContainers[containerId] = idleSince
        }
      }
    }
    idleContainers = newIdleContainers
  }

  @Scheduled(
      fixedRateString = "#{@config.ide.removeCheckRate}",
      initialDelayString = "#{@config.ide.removeCheckRate}"
  )
  protected fun removeShutdownContainers() {
    val thresholdDate = Date.from(Instant.now().minusMillis(config.ide.removeThreshold))
    log.debug("Removing IDE containers exited before $thresholdDate")
    getAllIdeContainers(ListContainersParam.withStatusExited()).forEach { container ->
      val containerId = container.id()
      val inspection = containerService.inspectContainer(containerId)
      removeIdeContainer(containerId) {
        inspection.state().finishedAt().before(thresholdDate)
      }
    }
  }
}

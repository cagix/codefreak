package org.codefreak.codefreak.service.evaluation

import java.time.Instant
import java.util.UUID
import org.codefreak.codefreak.entity.Answer
import org.codefreak.codefreak.entity.Assignment
import org.codefreak.codefreak.entity.AssignmentStatus
import org.codefreak.codefreak.entity.Evaluation
import org.codefreak.codefreak.entity.EvaluationStepDefinition
import org.codefreak.codefreak.entity.EvaluationStepResult
import org.codefreak.codefreak.entity.EvaluationStepStatus
import org.codefreak.codefreak.entity.Feedback
import org.codefreak.codefreak.entity.Task
import org.codefreak.codefreak.repository.EvaluationRepository
import org.codefreak.codefreak.repository.EvaluationStepDefinitionRepository
import org.codefreak.codefreak.repository.TaskRepository
import org.codefreak.codefreak.service.AssignmentStatusChangedEvent
import org.codefreak.codefreak.service.BaseService
import org.codefreak.codefreak.service.EntityNotFoundException
import org.codefreak.codefreak.service.EvaluationStatusUpdatedEvent
import org.codefreak.codefreak.service.EvaluationStepStatusUpdatedEvent
import org.codefreak.codefreak.service.IdeService
import org.codefreak.codefreak.service.SubmissionDeadlineReachedEvent
import org.codefreak.codefreak.service.SubmissionService
import org.codefreak.codefreak.service.TaskService
import org.codefreak.codefreak.service.evaluation.runner.CommentRunner
import org.codefreak.codefreak.service.file.FileService
import org.codefreak.codefreak.util.PositionUtil
import org.codefreak.codefreak.util.orNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EvaluationService : BaseService() {
  @Autowired
  private lateinit var submissionService: SubmissionService

  @Autowired
  private lateinit var evaluationRepository: EvaluationRepository

  @Autowired
  private lateinit var ideService: IdeService

  @Autowired
  private lateinit var fileService: FileService

  @Autowired
  private lateinit var taskRepository: TaskRepository

  @Autowired
  private lateinit var taskService: TaskService

  @Autowired
  private lateinit var stepService: EvaluationStepService

  @Autowired
  private lateinit var evaluationQueue: EvaluationQueue

  @Autowired
  private lateinit var eventPublisher: ApplicationEventPublisher

  @Autowired
  private lateinit var runnerService: EvaluationRunnerService

  @Autowired
  private lateinit var evaluationStepDefinitionRepository: EvaluationStepDefinitionRepository

  private val log = LoggerFactory.getLogger(this::class.java)

  @Transactional
  fun startAssignmentEvaluation(assignmentId: UUID): List<Evaluation> {
    val submissions = submissionService.findSubmissionsOfAssignment(assignmentId)
    return submissions.flatMap { it.answers }.mapNotNull {
      try {
        // this will never be run by a student so force file saving should be safe
        startEvaluation(it, forceSaveFiles = true)
      } catch (e: IllegalStateException) {
        // evaluation is already fresh or running
        log.debug("Not queuing evaluation for answer ${it.id}: ${e.message}")
        null
      }
    }
  }

  @Synchronized
  fun startEvaluation(answer: Answer, forceSaveFiles: Boolean = false): Evaluation {
    ideService.saveAnswerFiles(answer, forceSaveFiles)
    check(!isEvaluationUpToDate(answer)) { "Evaluation is up to date." }
    check(!isEvaluationScheduled(answer.id)) { "Evaluation is already scheduled." }
    val digest = fileService.getCollectionMd5Digest(answer.id)
    val evaluation = getOrCreateEvaluationByDigest(answer, digest)

    // schedule all non-finished steps for automated evaluation
    evaluation.evaluationSteps
        .filter { stepService.stepNeedsExecution(it) }
        .forEach {
          it.reset()
          evaluationQueue.insert(it)
        }
    return evaluation
  }

  fun getLatestEvaluation(answerId: UUID) = evaluationRepository.findFirstByAnswerIdOrderByCreatedAtDesc(answerId)

  /**
   * Check if we are still waiting for evaluation steps to be finished
   */
  fun isEvaluationScheduled(answerId: UUID): Boolean {
    val status = getLatestEvaluationStatus(answerId) ?: return false
    return status > EvaluationStepStatus.PENDING && status < EvaluationStepStatus.FINISHED
  }

  fun getLatestEvaluationStatus(answerId: UUID): EvaluationStepStatus? {
    return getLatestEvaluation(answerId).map { it.stepStatusSummary }.orNull()
  }

  fun getOrCreateEvaluationByDigest(answer: Answer, digest: ByteArray): Evaluation {
    return evaluationRepository.findFirstByAnswerIdAndFilesDigestOrderByCreatedAtDesc(answer.id, digest)
        .map { if (it.evaluationSettingsFrom != answer.task.evaluationSettingsChangedAt) createEvaluation(answer, digest) else it }
        .orElseGet { createEvaluation(answer, digest) }
  }

  @Transactional
  fun invalidateEvaluations(task: Task) {
    if (log.isDebugEnabled) {
      log.debug("Invalidating evaluations of task '${task.title}' (taskId=${task.id})")
    }
    task.evaluationSettingsChangedAt = Instant.now()
  }

  @Transactional
  fun invalidateEvaluations(assignment: Assignment) {
    assignment.tasks.forEach(this::invalidateEvaluations)
  }

  /**
   * Create a fresh evaluation for the given answer + digest combination.
   * All steps are in a pending state
   */
  @Transactional
  fun createEvaluation(answer: Answer, filesDigest: ByteArray): Evaluation {
    val evaluation = Evaluation(
        answer,
        filesDigest,
        answer.task.evaluationSettingsChangedAt
    )
    // add all steps as "pending" to the evaluation
    answer.task.evaluationStepDefinitions
        .filter { it.active }
        .forEach { stepService.addStepToEvaluation(evaluation, it) }
    return saveEvaluation(evaluation).also {
      eventPublisher.publishEvent(EvaluationStatusUpdatedEvent(it, it.stepStatusSummary))
    }
  }

  fun saveEvaluation(evaluation: Evaluation) = evaluationRepository.save(evaluation)

  @Transactional
  fun addCommentFeedback(answer: Answer, digest: ByteArray, feedback: Feedback): Feedback {
    // find out if evaluation has a comment step definition
    val stepDefinition = answer.task.evaluationStepDefinitions.find { it.runnerName == CommentRunner.RUNNER_NAME }
        ?: throw IllegalArgumentException("Task has no 'comments' evaluation step")
    val evaluation = getOrCreateEvaluationByDigest(answer, digest)
    val evaluationStep = evaluation.evaluationSteps.find { it.definition == stepDefinition }
        ?: throw RuntimeException("Evaluation does not contain a 'comments' step")
    evaluationStep.addFeedback(feedback)

    // mark this evaluation step as "finished" which means "teacher evaluated this answer manually"
    evaluationStep.status = EvaluationStepStatus.FINISHED
    evaluationStep.finishedAt = Instant.now()
    // if there is any failed feedback the overall step result is "failed"
    evaluationStep.result = when {
      evaluationStep.feedback.any { it.isFailed } -> EvaluationStepResult.FAILED
      else -> EvaluationStepResult.SUCCESS
    }
    saveEvaluation(evaluation)

    // there is no real definition of "done" for comments, so we trigger a FINISHED event
    // each time a new comment is added to this answer
    eventPublisher.publishEvent(EvaluationStepStatusUpdatedEvent(evaluationStep, EvaluationStepStatus.FINISHED))
    return feedback
  }

  fun isEvaluationUpToDate(answer: Answer): Boolean {
    // check if any evaluation has been run at all
    val evaluation = getLatestEvaluation(answer.id).orNull() ?: return false
    // check if evaluation settings have been changed since the evaluation was run
    if (evaluation.evaluationSettingsFrom != answer.task.evaluationSettingsChangedAt) {
      return false
    }
    // check if evaluation has been run for latest file state
    if (!evaluation.filesDigest.contentEquals(fileService.getCollectionMd5Digest(answer.id))) {
      return false
    }
    // check if any step needs execution
    if (evaluation.evaluationSteps.any { stepService.stepNeedsExecution(it) }) {
      return false
    }
    // allow to re-run if any of the steps errored
    if (evaluation.stepsResultSummary === EvaluationStepResult.ERRORED) {
      return false
    }
    return true
  }

  fun getEvaluation(evaluationId: UUID): Evaluation {
    return evaluationRepository.findById(evaluationId).orElseThrow { EntityNotFoundException("Evaluation not found") }
  }

  @EventListener
  @Transactional
  fun onSubmissionDeadlineReached(event: SubmissionDeadlineReachedEvent) {
    log.info("Automatically trigger evaluation for answers of submission ${event.submissionId}")
    submissionService.findSubmission(event.submissionId).answers.forEach { answer ->
      try {
        startEvaluation(answer, forceSaveFiles = true)
      } catch (e: IllegalStateException) {
        // evaluation has already been triggered
      }
    }
  }

  @EventListener
  @Transactional
  fun onAssignmentClosed(event: AssignmentStatusChangedEvent) {
    if (event.status != AssignmentStatus.CLOSED) {
      return
    }
    log.info("Automatically trigger evaluation for answers of ${event.assignmentId}")
    startAssignmentEvaluation(event.assignmentId)
  }

  fun findEvaluationStepDefinition(id: UUID): EvaluationStepDefinition = evaluationStepDefinitionRepository.findById(id)
      .orElseThrow { EntityNotFoundException("Evaluation step definition not found") }

  fun saveEvaluationStepDefinition(definition: EvaluationStepDefinition) = evaluationStepDefinitionRepository.save(definition)

  @Transactional
  fun setEvaluationStepDefinitionPosition(evaluationStepDefinition: EvaluationStepDefinition, newPosition: Long) {
    val task = evaluationStepDefinition.task

    PositionUtil.move(task.evaluationStepDefinitions, evaluationStepDefinition.position.toLong(), newPosition, { position.toLong() }, { position = it.toInt() })

    evaluationStepDefinitionRepository.saveAll(task.evaluationStepDefinitions)
    taskRepository.save(task)
  }

  @Transactional
  fun deleteEvaluationStepDefinition(evaluationStepDefinition: EvaluationStepDefinition) {
    evaluationStepDefinition.task.run {
      evaluationStepDefinitions.filter { it.position > evaluationStepDefinition.position }.forEach { it.position-- }
      evaluationStepDefinitionRepository.saveAll(evaluationStepDefinitions)
    }
    evaluationStepDefinitionRepository.delete(evaluationStepDefinition)
  }

  @Transactional
  fun updateEvaluationStepDefinition(evaluationStepDefinition: EvaluationStepDefinition, title: String?, active: Boolean?, timeout: Long?, options: Map<String, Any>?): EvaluationStepDefinition {
    title?.let {
      evaluationStepDefinition.title = it
    }
    active?.let {
      evaluationStepDefinition.active = it
    }
    options?.let {
      evaluationStepDefinition.options = it
    }
    evaluationStepDefinition.timeout = timeout
    runnerService.validateRunnerOptions(evaluationStepDefinition)
    saveEvaluationStepDefinition(evaluationStepDefinition)
    taskService.invalidateLatestEvaluations(evaluationStepDefinition.task)

    return evaluationStepDefinition
  }
}

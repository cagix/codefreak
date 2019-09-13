package de.code_freak.codefreak.service.evaluation.runner

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import de.code_freak.codefreak.entity.Answer
import de.code_freak.codefreak.service.ContainerService
import de.code_freak.codefreak.service.evaluation.EvaluationRunner
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class CodeclimateRunner : EvaluationRunner {

  @Autowired
  private lateinit var containerService: ContainerService

  private val log = LoggerFactory.getLogger(this::class.java)

  override fun getName(): String {
    return "codeclimate"
  }

  override fun run(answer: Answer, options: Map<String, Any>): String {
    return containerService.runCodeclimate(answer)
  }

  override fun parseResultContent(content: ByteArray): Any {
    return try {
      val mapper = ObjectMapper()
      val results = mapper.readValue(content, Array<Result>::class.java)
      Content(results.filterIsInstance<Issue>())
    } catch (e: Exception) {
      log.error(e.message)
      Content(emptyList(), "Error displaying results")
    }
  }

  private class Content(val issues: List<Issue>, val error: String? = null)

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes(
      Type(value = Issue::class, name = "issue"),
      Type(value = Measurement::class, name = "measurement")
  )
  private open class Result {
    var engine_name = ""
  }

  private class Measurement : Result() {
    var value = 0
    var name = ""
  }

  private class Issue : Result() {
    var description = ""
    var check_name = ""
    var content: Content? = null
    var categories = listOf<String>()
    var location: Location = Location()
    var severity: String? = null
    var fingerprint: String? = null

    private class Content {
      var body = ""
    }

    // TODO support other location formats (needs custom deserializer)
    //      see https://github.com/codeclimate/spec/blob/master/SPEC.md#locations
    private class Location {
      var path = ""
      var lines = Lines()

      private class Lines {
        var begin = 0
        var end = 0
      }
    }
  }
}
package org.codefreak.codefreak.service

import com.nhaarman.mockitokotlin2.any
import org.codefreak.codefreak.entity.FileCollection
import org.codefreak.codefreak.repository.FileCollectionRepository
import org.codefreak.codefreak.service.file.JpaFileService
import org.junit.Before
import org.junit.Test
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.util.Optional
import java.util.UUID

class JpaFileServiceTest {
  private val collectionId = UUID(0, 0)
  private val filePath = "file.txt"
  private val directoryPath = "some/path/"

  @Mock
  lateinit var fileCollectionRepository: FileCollectionRepository
  @InjectMocks
  val fileService = JpaFileService()

  @Before
  fun init() {
    MockitoAnnotations.initMocks(this)

    val fileCollection = FileCollection(collectionId)
    `when`(fileCollectionRepository.findById(any())).thenReturn(Optional.of(fileCollection))
  }

  @Test
  fun `createFile creates an empty file`() {
    createFile(filePath)
    assert(containsFile(filePath))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `createFile throws when the path already exists`() {
    createFile(filePath)
    createFile(filePath) // Throws because file already exists
  }

  @Test(expected = IllegalArgumentException::class)
  fun `createFile throws on empty path name`() {
    createFile("")
  }

  @Test
  fun `createFile keeps other files intact`() {
    createFile("other.txt")
    createDirectory("aDirectory")
    createFile(filePath)

    assert(fileService.containsFile(collectionId, filePath))
    assert(fileService.containsFile(collectionId, "other.txt"))
    assert(fileService.containsDirectory(collectionId, "aDirectory"))
  }

  @Test
  fun `createDirectory creates an empty directory`() {
    createDirectory(directoryPath)
    assert(containsDirectory(directoryPath))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `createDirectory throws when the path already exists`() {
    createDirectory(directoryPath)
    createDirectory(directoryPath) // Throws because directory already exists
  }

  @Test(expected = IllegalArgumentException::class)
  fun `createDirectory throws on empty path name`() {
    createFile("")
  }

  @Test
  fun `createDirectory keeps other files intact`() {
    createFile("other.txt")
    createDirectory("aDirectory")
    createDirectory(directoryPath)

    assert(containsFile("other.txt"))
    assert(containsDirectory("aDirectory"))
    assert(containsDirectory(directoryPath))
  }

  @Test
  fun `deleteFile deletes existing file`() {
    createFile(filePath)

    deleteFile(filePath)

    assert(!containsFile(filePath))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `deleteFile throws when path does not exist`() {
    deleteFile(filePath)
  }

  @Test
  fun `deleteFile keeps other files and directories intact`() {
    createFile(filePath)
    createFile("DO_NOT_DELETE.txt")
    createDirectory(directoryPath)

    deleteFile(filePath)

    assert(!containsFile(filePath))
    assert(containsFile("DO_NOT_DELETE.txt"))
    assert(containsDirectory(directoryPath))
  }

  @Test
  fun `deleteDirectory deletes existing directory`() {
    createDirectory(directoryPath)

    deleteDirectory(directoryPath)

    assert(!containsDirectory(directoryPath))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `deleteDirectory throws when path does not exist`() {
    deleteDirectory(directoryPath)
  }

  @Test
  fun `deleteDirectory keeps other files and directories intact`() {
    createFile(filePath)
    createDirectory("DO_NOT_DELETE")
    createDirectory(directoryPath)

    deleteDirectory(directoryPath)

    assert(containsFile(filePath))
    assert(containsDirectory("DO_NOT_DELETE"))
    assert(!containsDirectory(directoryPath))
  }

  @Test
  fun `deleteDirectory deletes directory content recursively`() {
    val directoryToDelete = directoryPath
    val fileToRecursivelyDelete = "$directoryPath/$filePath"
    val directoryToRecursivelyDelete = "$directoryPath/$directoryPath"
    val fileToBeUnaffected = filePath

    createDirectory(directoryToDelete)
    createFile(fileToRecursivelyDelete)
    createDirectory(directoryToRecursivelyDelete)
    createFile(fileToBeUnaffected)

    deleteDirectory(directoryToDelete)

    assert(!containsDirectory(directoryToDelete))
    assert(!containsFile(fileToRecursivelyDelete))
    assert(!containsDirectory(directoryToRecursivelyDelete))
    assert(containsFile(fileToBeUnaffected))
  }

  @Test
  fun `filePutContents puts the file contents correctly`() {
    val contents = byteArrayOf(42)
    createFile(filePath)

    filePutContents(filePath, contents)

    assert(containsFile(filePath))
    assert(equals(fileService.getFileContents(collectionId, filePath), contents))
  }

  private fun equals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) {
      return false
    }

    a.forEachIndexed { index, byte ->
      if (byte != b[index]) {
        return false
      }
    }

    return true
  }

  @Test(expected = IllegalArgumentException::class)
  fun `filePutContents throws for directories`() {
    createDirectory(directoryPath)

    filePutContents(directoryPath, byteArrayOf(42))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `filePutContents throws if path does not exist`() {
    filePutContents(filePath, byteArrayOf(42))
  }

  private fun createFile(path: String) = fileService.createFile(collectionId, path)

  private fun createDirectory(path: String) = fileService.createDirectory(collectionId, path)

  private fun deleteFile(path: String) = fileService.deleteFile(collectionId, path)

  private fun deleteDirectory(path: String) = fileService.deleteDirectory(collectionId, path)

  private fun containsFile(path: String): Boolean = fileService.containsFile(collectionId, path)

  private fun containsDirectory(path: String): Boolean = fileService.containsDirectory(collectionId, path)

  private fun filePutContents(path: String, contents: ByteArray) = fileService.filePutContents(collectionId, path, contents)
}

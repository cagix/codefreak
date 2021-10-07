package org.codefreak.codefreak.service.workspace

import java.util.UUID

/**
 * Interface for describing the demand for a Workspace
 */
data class WorkspaceConfiguration(
  /**
   * Identity of the user that will be authorized to access the workspace.
   * This should be a unique identity of the user (id or username).
   * Will be used for creating the JWT "sub" claim.
   */
  val user: String,

  /**
   * Collection-ID that will be used for reading/writing files from/to a workspace.
   * If the workspace is marked as read-only the collection will only be extracted
   * and not stored back to the database.
   */
  val collectionId: UUID,

  /**
   * Indicate if this workspace is read-only, meaning the files will not be saved
   * back to the database.
   */
  val isReadOnly: Boolean,

  /**
   * Map of executable name to content of scripts that will be added to PATH
   */
  val scripts: Map<String, String>,

  /**
   * Name of the container image that will be used for creating the workspace.
   * The default value will be the current companion image but teachers might be able to create
   * their own custom images in the future.
   */
  val imageName: String
)

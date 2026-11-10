package com.haifasoft.haifassh.model

/**
 * A folder in the session tree. [parentId] is empty for top-level folders.
 * Mutable with defaults so the IntelliJ XML serializer can persist it.
 */
class SshFolder {
    var id: String = ""
    var name: String = "New Folder"
    var parentId: String = ""

    /** `#RRGGBB` tint applied to the folder icon and name in the tree (blank = default). */
    var color: String = ""

    /**
     * Manual ordering among the sub-folders of [parentId], lowest first. Ties
     * (and the `0` every pre-ordering folder loads with) fall back to name order,
     * so a tree that was never reordered stays alphabetical.
     */
    var position: Int = 0
}

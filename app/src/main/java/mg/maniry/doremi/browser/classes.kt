package mg.maniry.doremi.browser

class RemoteSolfa(val id: Int, val driveId: String, val name: String, val dld: Int)

class ActionTypes {
    companion object {
        const val OPEN = 0
        const val SHARE = 1
        const val DELETE = 2
        const val PUBLISH = 3
    }
}

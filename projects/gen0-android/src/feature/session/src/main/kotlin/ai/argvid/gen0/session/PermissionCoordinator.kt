package ai.argvid.gen0.session

enum class AppPermission {
    Camera,
}

enum class PermissionStatus {
    NotAsked,
    Requested,
    Granted,
    Denied,
}

class PermissionCoordinator {
    private val statuses = AppPermission.entries.associateWith { PermissionStatus.NotAsked }.toMutableMap()

    fun request(permission: AppPermission): AppPermission? = when (statuses.getValue(permission)) {
        PermissionStatus.NotAsked -> permission.also { statuses[it] = PermissionStatus.Requested }
        PermissionStatus.Requested,
        PermissionStatus.Granted,
        PermissionStatus.Denied,
        -> null
    }

    fun resolve(permission: AppPermission, granted: Boolean) {
        if (statuses.getValue(permission) != PermissionStatus.Requested) return
        statuses[permission] = if (granted) PermissionStatus.Granted else PermissionStatus.Denied
    }

    fun status(permission: AppPermission): PermissionStatus = statuses.getValue(permission)

    fun cancelPendingRequest() {
        statuses.replaceAll { _, status ->
            if (status == PermissionStatus.Requested) PermissionStatus.NotAsked else status
        }
    }
}

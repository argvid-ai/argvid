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
        PermissionStatus.NotAsked,
        PermissionStatus.Denied,
        -> permission.also { statuses[it] = PermissionStatus.Requested }
        PermissionStatus.Requested,
        PermissionStatus.Granted,
        -> null
    }

    fun resolve(permission: AppPermission, granted: Boolean) {
        statuses[permission] = if (granted) PermissionStatus.Granted else PermissionStatus.Denied
    }

    fun synchronize(permission: AppPermission, granted: Boolean) {
        statuses[permission] = if (granted) PermissionStatus.Granted else PermissionStatus.Denied
    }

    fun status(permission: AppPermission): PermissionStatus = statuses.getValue(permission)

    fun cancelPendingRequest() {
        statuses.replaceAll { _, status ->
            if (status == PermissionStatus.Requested) PermissionStatus.NotAsked else status
        }
    }
}

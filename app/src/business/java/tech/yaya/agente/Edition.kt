package tech.yaya.agente

/** Business edition (agento business): the home screen is the dashboard. */
object Edition {
    const val CLIENT = false
    val HOME: Class<*> = DashboardActivity::class.java
}

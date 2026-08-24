package tech.yaya.agente

/** Business edition (agento business): the home screen is the dashboard. */
object Edition {
    const val CLIENT = false
    val HOME: Class<*> = DashboardActivity::class.java
    /** After sign-in on a phone with no business yet. */
    val FIRST_RUN: Class<*> = RegistrationActivity::class.java
}

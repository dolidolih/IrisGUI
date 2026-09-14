// ConfigPageDocumentProvider.java
package party.qwer.irisgui.backend

import party.qwer.irisgui.AppConfig

class PageRenderer {
    companion object {
        fun renderDashboard(): String {
            var html = AssetManager.readFile("dashboard.html")
            html = html.replace("CURRENT_WEB_ENDPOINT", AppConfig.webEndpoint)
            html = html.replace("CURRENT_BOT_NAME", AppConfig.botName)
            html = html.replace("CURRENT_DB_RATE", AppConfig.dbPollingRate.toString())
            html = html.replace("CURRENT_SEND_RATE", AppConfig.messageSendRate.toString())
            html = html.replace("CURRENT_BOT_PORT", AppConfig.serverPort.toString())
            return html
        }
    }
}
package app.stillroom.background

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.stillroom.MainActivity
import app.stillroom.R
import app.stillroom.domain.*

open class HouseholdWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) { HouseholdWork.updateSoon(context) }
    override fun onEnabled(context:Context) { HouseholdWork.schedule(context);HouseholdWork.updateSoon(context) }
}
class ChoresWidget:HouseholdWidget()
class ShoppingWidget:HouseholdWidget()
class ScanWidget:HouseholdWidget()
class TasksWidget:HouseholdWidget()
object WidgetRenderer {
    fun update(context:Context,snapshot:TodaySnapshot) {
        val manager=AppWidgetManager.getInstance(context)
        val definitions=listOf(Triple(TasksWidget::class.java,"Tasks","tasks"),Triple(ChoresWidget::class.java,"Chores","chores"),Triple(ShoppingWidget::class.java,"Shopping","shopping"),Triple(ScanWidget::class.java,"Scan","scan"))
        for((type,title,target)in definitions) {
            val ids=manager.getAppWidgetIds(ComponentName(context,type))
            if(ids.isEmpty())continue
            val permission=!snapshot.accessDenied && when(target) { "tasks"->HouseholdAccess.has(snapshot.account?.permissions,"TASKS");"chores"->HouseholdAccess.has(snapshot.account?.permissions,"CHORES");"shopping"->ShoppingAccess.allowed(snapshot.account?.permissions);else->snapshot.scan }
            val body=when {
                snapshot.account==null->"Open Stillroom and connect an account."
                !permission->"Access denied for this account."
                target=="tasks"->tasksForUser(snapshot.tasks,snapshot.account!!).take(5).joinToString("\n") { it.houseText("name") }.ifBlank { "No open personal tasks in cache." }
                target=="chores"->snapshot.chores.take(4).joinToString("\n") { it.catalogText("chore_name") }.ifBlank { "No due chores in cache." }
                target=="shopping"->snapshot.lists.take(4).joinToString("\n") { list->"${list.catalogText("name")}: ${snapshot.shopping.count { it.catalogId("shopping_list_id")==list.catalogId("id") }} items" }.ifBlank { "No cached shopping lists." }
                else->"Barcode, QR, or manual entry"
            }
            val views=RemoteViews(context.packageName,R.layout.household_widget)
            views.setTextViewText(R.id.widget_title,"$title · ${snapshot.account?.username ?: "Stillroom"}")
            views.setTextViewText(R.id.widget_content,body)
            views.setTextViewText(R.id.widget_action,if(target=="scan")"Open scanner" else "Open $title · cached")
            val intent=Intent(context,MainActivity::class.java).putExtra("stillroom_destination",target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val click=PendingIntent.getActivity(context,target.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_action,click)
            manager.updateAppWidget(ids,views)
        }
    }
}

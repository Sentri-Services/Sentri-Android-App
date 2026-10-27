package com.sentri.nativeapp.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import androidx.compose.ui.res.stringResource

/** Android-local adapter for the four-slot Sentri-UI Workbench tile. */
data class WorkTileTodo(val main:String="",val red:String="",val quiet:String="",val tick:Boolean=false,val mainRed:Boolean=false)

@Composable
fun SentriWorkTile(title:String,batch:String,todo:WorkTileTodo,day:Int,days:Int,overDays:Int,onClick:()->Unit,modifier:Modifier=Modifier) {
    val c=SentriTheme.colors
    Surface(modifier=modifier.fillMaxWidth().height(160.dp).clickable(onClick=onClick),shape=RoundedCornerShape(16.dp),color=c.paper,
        border=BorderStroke(1.dp,c.line)) {
        Column(Modifier.padding(start=12.dp,end=12.dp,top=13.dp,bottom=11.dp)) {
            Text(title,fontFamily=SentriType.sans,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=c.ink,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(batch,Modifier.padding(top=4.dp),fontFamily=SentriType.sans,fontSize=11.sp,color=c.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(23.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                if(todo.tick)SentriIcon("check",null,Modifier.width(14.dp),c.green)
                if(todo.main.isNotEmpty())Text(todo.main,fontFamily=SentriType.sans,fontSize=11.sp,fontWeight=FontWeight.Medium,
                    color=if(todo.mainRed)c.red else c.ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(todo.red.isNotEmpty())Text("· ${todo.red}",fontFamily=SentriType.sans,fontSize=10.sp,color=c.red,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(todo.quiet.isNotEmpty())Text(todo.quiet,fontFamily=SentriType.sans,fontSize=11.sp,color=c.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                repeat(days.coerceIn(1,10)) { i ->
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(if(i<day)c.rowIconInk else c.well))
                }
                repeat(overDays.coerceIn(0,5)) {
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.red))
                }
            }
            Text(if(overDays>0)stringResource(R.string.wb_day_overdue,overDays) else stringResource(R.string.wb_day_of,day,days),
                Modifier.padding(top=5.dp),fontFamily=SentriType.sans,fontSize=10.sp,
                color=c.muted,maxLines=1)
        }
    }
}

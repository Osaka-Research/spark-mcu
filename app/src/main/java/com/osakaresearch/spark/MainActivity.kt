package com.osakaresearch.spark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.osakaresearch.spark.mcp.McpServer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.osakaresearch.spark.ai.SparkDeviceContext.initialize(applicationContext)
        McpServer.start(applicationContext)
        setContent {
            SparkApp()
        }
    }
}

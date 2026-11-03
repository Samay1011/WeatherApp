package com.samay.weatherapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samay.weatherapp.ui.HomeScreen
import com.samay.weatherapp.ui.WeatherViewModel
import com.samay.weatherapp.ui.theme.WeatherAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeatherAppTheme {
                val viewModel: WeatherViewModel = viewModel()
                HomeScreen(viewModel = viewModel)
            }
        }
    }
}
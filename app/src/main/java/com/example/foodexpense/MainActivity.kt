package com.example.foodexpense

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.foodexpense.ui.navigation.AppRoot
import com.example.foodexpense.ui.theme.FoodExpenseTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoodExpenseTheme {
                AppRoot()
            }
        }
    }
}

package com.example.blemesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.blemesh.ui.MainScreen
import com.example.blemesh.ui.MainViewModel
import com.example.blemesh.ui.theme.BleMeshTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory((application as MeshApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BleMeshTheme {
                MainScreen(viewModel)
            }
        }
    }
}

package com.example.igguard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editFriends = findViewById<EditText>(R.id.editFriends)
        val saveButton = findViewById<Button>(R.id.buttonSave)
        val openSettingsButton = findViewById<Button>(R.id.buttonOpenSettings)

        // Показываем текущий список (по одному username на строку)
        editFriends.setText(FriendsStore.getFriends(this).joinToString("\n"))

        saveButton.setOnClickListener {
            val usernames = editFriends.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            FriendsStore.setFriends(this, usernames)
            Toast.makeText(this, "Сохранено: ${usernames.size} друзей", Toast.LENGTH_SHORT).show()
        }

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}

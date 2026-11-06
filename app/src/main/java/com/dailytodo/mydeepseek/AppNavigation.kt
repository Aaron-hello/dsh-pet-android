package com.dailytodo.mydeepseek

import android.app.Activity
import android.content.Context
import android.content.Intent

object AppNavigation {
    fun open(context: Context, page: String, pet: String = "android-0") {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("openPage", page).putExtra("chatPetId", pet)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

package com.example.krishisetuapp

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)

        btnLogin.setOnClickListener {

            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Fields cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "Signing in..."

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    checkUserRole()
                }
                .addOnFailureListener { e ->
                    btnLogin.isEnabled = true
                    btnLogin.text = "Sign In"
                    Toast.makeText(this, "Login Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun checkUserRole() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->

                val role = doc.getString("role")
                var navigated = false

                when (role) {
                    "admin" -> {
                        startActivity(Intent(this, AdminDashboardActivity::class.java))
                        navigated = true
                    }
                    "farmer" -> {
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                        navigated = true
                    }
                    else -> {
                        resetLoginButton()
                        Toast.makeText(this, "Invalid role", Toast.LENGTH_SHORT).show()
                    }
                }
                if (navigated) {
                    finish()
                }
            }
            .addOnFailureListener {
                resetLoginButton()
                Toast.makeText(this, "Error fetching role", Toast.LENGTH_SHORT).show()
            }
    }

    private fun resetLoginButton() {
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        btnLogin.isEnabled = true
        btnLogin.text = "Sign In"
    }
}

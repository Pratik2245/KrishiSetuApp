package com.example.krishisetuapp

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val name = findViewById<EditText>(R.id.etName)
        val phone = findViewById<EditText>(R.id.etPhone)
        val village = findViewById<EditText>(R.id.etVillage)
        val email = findViewById<EditText>(R.id.etEmail)
        val password = findViewById<EditText>(R.id.etPassword)
        val confirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        val registerBtn = findViewById<Button>(R.id.btnRegister)
        val tvLogin = findViewById<TextView>(R.id.tvLogin)

        registerBtn.setOnClickListener {

            val userName = name.text.toString().trim()
            val userPhone = phone.text.toString().trim()
            val userVillage = village.text.toString().trim()
            val userEmail = email.text.toString().trim()
            val userPass = password.text.toString().trim()
            val userConfirmPass = confirmPassword.text.toString().trim()

            // 🔥 Validation
            if (userName.isEmpty() || userPhone.isEmpty() ||
                userVillage.isEmpty() || userEmail.isEmpty() ||
                userPass.isEmpty() || userConfirmPass.isEmpty()) {

                Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (userPass.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (userPass != userConfirmPass) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            registerBtn.isEnabled = false
            registerBtn.text = "Creating Account..."

            // 🔐 Create user in Firebase Auth
            auth.createUserWithEmailAndPassword(userEmail, userPass)
                .addOnSuccessListener { result ->

                    val uid = result.user!!.uid

                    // 🔥 Save full details in Firestore
                    val userData = hashMapOf(
                        "uid" to uid,
                        "name" to userName,
                        "phone" to userPhone,
                        "village" to userVillage,
                        "email" to userEmail,
                        "role" to "farmer",   // everyone becomes farmer
                        "createdAt" to System.currentTimeMillis()
                    )

                    db.collection("users").document(uid)
                        .set(userData)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Registered Successfully", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        .addOnFailureListener { e ->
                            resetRegisterButton()
                            Toast.makeText(this, "Firestore Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
                .addOnFailureListener { e ->
                    resetRegisterButton()
                    Toast.makeText(this, "Auth Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        tvLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun resetRegisterButton() {
        val registerBtn = findViewById<Button>(R.id.btnRegister)
        registerBtn.isEnabled = true
        registerBtn.text = "Create Account"
    }
}

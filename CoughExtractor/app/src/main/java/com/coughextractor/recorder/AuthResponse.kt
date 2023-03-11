package com.coughextractor.recorder

data class AuthResponse(
    val token: String?,
    val username: String?,
    val email: String?,
    val user_id: Int?
)

data class ExaminationsResponse(
    val examinations: List<Examination>
)

data class Examination(
    val examination_name: String,
    val files: List<String>,
    val id: Int,
    val patient_key: Int,
    val patient_surname: String
)

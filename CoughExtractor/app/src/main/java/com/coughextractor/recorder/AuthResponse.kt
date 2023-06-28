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
    val examination_name: String = "",
    val files: List<String> = listOf(),
    val id: Int = -1,
    val patient_key: Int = -1,
    val patient_surname: String = ""
)

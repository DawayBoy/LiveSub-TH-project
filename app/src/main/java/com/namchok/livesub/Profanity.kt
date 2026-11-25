package com.namchok.livesub

/**
 * คำหยาบภาษาอังกฤษ -> คำไทยที่ความแรงใกล้เคียง (ใช้เมื่อเปิด "แปลคำหยาบตรงตัว")
 * ML Kit มักแปลคำหยาบให้สุภาพลงหรือข้ามไป จึงแทนคำเหล่านี้เป็นภาษาไทยก่อนส่งไปแปล
 * วลีที่ยาวกว่าจะถูกแทนก่อนเสมอ (เรียงตามความยาวใน Glossary)
 * แก้/เพิ่มเองได้ผ่าน "คำศัพท์กำหนดเอง" ในแอป (กฎของผู้ใช้มาก่อนเสมอ)
 */
object Profanity {
    val rules: List<Pair<String, String>> = listOf(
        "shut the fuck up" to "หุบปากไปเลย",
        "what the fuck" to "อะไรวะเนี่ย",
        "what the hell" to "อะไรวะ",
        "who the fuck" to "ใครวะ",
        "where the fuck" to "ที่ไหนวะ",
        "why the fuck" to "ทำไมวะ",
        "the fuck" to "วะ",
        "son of a bitch" to "ไอ้ระยำ",
        "sons of bitches" to "พวกระยำ",
        "mother fucker" to "ไอ้ชาติชั่ว",
        "motherfucker" to "ไอ้ชาติชั่ว",
        "motherfuckers" to "พวกชาติชั่ว",
        "fuck you" to "ไปตายซะ",
        "fuck off" to "ไสหัวไป",
        "fuck me" to "ฉิบหายแล้วกู",
        "fucked up" to "พังยับเยิน",
        "fucking" to "แม่ง",
        "fuckin" to "แม่ง",
        "fucked" to "ซวยฉิบหาย",
        "fucker" to "ไอ้เวร",
        "fuck" to "เหี้ย",
        "holy shit" to "ฉิบหายแล้ว",
        "bullshit" to "ไร้สาระสิ้นดี",
        "shitty" to "ห่วยแตก",
        "shit" to "ฉิบหาย",
        "piece of shit" to "ไอ้สวะ",
        "asshole" to "ไอ้สารเลว",
        "assholes" to "พวกสารเลว",
        "bastard" to "ไอ้ชาติหมา",
        "bastards" to "พวกชาติหมา",
        "bitches" to "พวกอีบ้า",
        "bitch" to "อีบ้า",
        "dickhead" to "ไอ้งั่ง",
        "goddamn" to "บ้าเอ๊ย",
        "god damn" to "บ้าเอ๊ย",
        "damn it" to "บ้าเอ๊ย",
        "dammit" to "บ้าเอ๊ย",
        "damn" to "บ้าจริง",
        "go to hell" to "ไปลงนรกซะ",
        "screw you" to "ไปตายซะ",
        "piss off" to "ไสหัวไป",
        "pissed off" to "โมโหฉิบหาย",
        "crap" to "ห่วยแตก",
    )
}

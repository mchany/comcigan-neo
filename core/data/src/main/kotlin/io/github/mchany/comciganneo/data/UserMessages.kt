package io.github.mchany.comciganneo.data

import io.github.mchany.comciganneo.comcigan.StructureChanged
import io.github.mchany.comciganneo.comcigan.TimetableUnavailable
import io.github.mchany.comciganneo.neis.*
import java.io.IOException

fun mealUserMessage(error: Throwable): String = when (error) {
    is NeisKeyRequired -> "급식 조회 설정이 없는 빌드예요. 최신 앱을 설치해 주세요."
    is NeisIncomplete -> "급식 자료를 모두 받지 못했어요. 잠시 후 다시 시도해 주세요."
    is NeisStructureChanged -> "급식 자료 형식이 바뀌었거나 읽을 수 없어요. 앱 업데이트를 확인해 주세요."
    is NeisApiException -> when (error.code) {
        "ERROR-290", "ERROR-300" -> "급식 서비스 인증에 실패했어요. 앱 업데이트를 확인해 주세요."
        "ERROR-337", "ERROR-336" -> "나이스 조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."
        else -> "나이스에서 조회를 완료하지 못했어요. (${error.code})"
    }
    is IOException -> "급식에 연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요."
    else -> "급식을 가져오지 못했어요. 잠시 후 다시 시도해 주세요."
}

fun userMessage(error: Throwable): String = when (error) {
    is TimetableUnavailable -> "아직 게시된 시간표가 없어요. 나중에 다시 확인해 주세요."
    is StructureChanged -> "시간표 형식이 바뀌었거나 자료를 읽을 수 없어요. 앱 업데이트를 확인해 주세요."
    is IOException -> "연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요."
    else -> "작업을 완료하지 못했어요. 잠시 후 다시 시도해 주세요."
}

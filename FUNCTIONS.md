# 엑셀 함수 지원 / Spreadsheet function support

0.3.0에서 116개의 함수 이름을 지원합니다. 아래에 명시한 인수 및 형태의 부분 집합이며 전체 Excel 호환을 뜻하지 않습니다. 앱의 함수 사전에서도 이름을 검색할 수 있습니다.

| 분야 | 함수 |
| --- | --- |
| 집계·통계 | `SUM`, `SUMPRODUCT`, `PRODUCT`, `SUMSQ`, `AVERAGE`, `MIN`, `MAX`, `COUNT`, `COUNTA`, `COUNTBLANK`, `MEDIAN`, `LARGE`, `SMALL`, `RANK`, `RANK.EQ`, `STDEV.S`, `STDEV.P`, `VAR.S`, `VAR.P` |
| 조건 집계 | `SUMIF`, `SUMIFS`, `COUNTIF`, `COUNTIFS`, `AVERAGEIF`, `AVERAGEIFS` |
| 조회·참조 | `VLOOKUP`, `HLOOKUP`, `XLOOKUP`, `INDEX`, `MATCH`, `CHOOSE`, `ROWS`, `COLUMNS` |
| 논리·오류 | `IF`, `IFS`, `IFERROR`, `IFNA`, `AND`, `OR`, `NOT`, `XOR`, `SWITCH`, `ISNUMBER`, `ISTEXT`, `ISBLANK`, `ISERROR`, `ISERR`, `ISNA`, `ISLOGICAL`, `N`, `T`, `NA` |
| 수학 | `ROUND`, `ROUNDUP`, `ROUNDDOWN`, `MROUND`, `INT`, `TRUNC`, `MOD`, `QUOTIENT`, `ABS`, `SIGN`, `POWER`, `SQRT`, `EXP`, `LN`, `LOG`, `LOG10`, `PI`, `SIN`, `COS`, `TAN`, `ASIN`, `ACOS`, `ATAN`, `DEGREES`, `RADIANS`, `CEILING`, `FLOOR` |
| 문자열 | `LEN`, `LEFT`, `RIGHT`, `MID`, `UPPER`, `LOWER`, `TRIM`, `CLEAN`, `CONCAT`, `CONCATENATE`, `TEXTJOIN`, `SUBSTITUTE`, `REPLACE`, `FIND`, `SEARCH`, `EXACT`, `VALUE`, `TEXT`, `REPT` |
| 날짜·시간 | `DATE`, `YEAR`, `MONTH`, `DAY`, `DAYS`, `EDATE`, `EOMONTH`, `WEEKDAY`, `TIME`, `HOUR`, `MINUTE`, `SECOND`, `TODAY`, `NOW` |
| 금융 | `PMT`, `PV`, `FV`, `NPER` |

## 동작·제한

- `=SUM(A1:A10)`, `=SUMIFS(C2:C10,A2:A10,"식비")`, `=XLOOKUP(E2,A2:A10,B2:B10)`처럼 일반 셀 주소와 직사각형 범위를 사용합니다. `$A$1`, `'시트 이름'!A1`, `_xlfn.XLOOKUP` 접두어를 지원합니다. 범위는 수식당 10,000셀, 참조 깊이는 64, 계산 작업은 50,000회를 제한합니다.
- 숫자·논리·문자열·오류를 구분합니다. 계산 실패를 이전 캐시 값으로 숨기지 않습니다. 미지원 함수/모드는 `#UNSUPPORTED(...)`, 순환 참조는 `#CYCLE!`, 범위/계산 제한은 `#LIMIT!`로 표시합니다. 외부 워크북·이름 정의·3D 참조·전체 행/열·배열 수식·스필 계산은 지원하지 않습니다.
- `XLOOKUP`: match_mode 0(정확히 일치), 2(와일드카드); search_mode 1(처음부터), -1(끝부터). 근사·이진 검색 모드는 명시적 미지원 오류입니다. `VLOOKUP/HLOOKUP` 근사 검색과 `MATCH` ±1은 정렬된 범위를 전제로 합니다. `INDEX`는 한 셀을 반환하는 행/열 번호만 지원합니다. `SUMPRODUCT`는 동일한 크기의 직접 범위 인수를 지원하고 배열 산술 인수는 지원하지 않습니다.
- 조건은 `=`, `<>`, `<`, `>`, `<=`, `>=`, `*`, `?`, `~` 이스케이프를 지원하며 와일드카드 패턴은 255자까지입니다. 다중 조건 범위는 크기가 같아야 합니다. 문자열/숫자 변환과 일부 빈 값 동작은 모든 Excel 버전과 완전히 일치하지 않을 수 있습니다.
- 날짜는 워크북의 1900/1904 기준을 사용합니다. 1900-02-29 가상 윤년 날짜를 처리합니다. `TODAY/NOW`는 기기의 시간대와 시각을 사용하며 다시 계산을 선택하면 갱신됩니다. `WEEKDAY` return_type 1/2/3을 지원합니다. 날짜 문자열 파싱과 역사적 날짜의 모든 Excel 특수 동작은 지원하지 않습니다.
- `TEXT`: 일반 숫자 패턴(`#`, `,`, `0`, `.`, `%`) 및 `yyyy-mm-dd`를 지원합니다. 통화/지역화/색상/조건 구간 사용자 형식은 미지원 오류입니다. `VALUE`는 소수점과 쉼표 구분 숫자만 지원합니다.
- `PMT/PV/FV/NPER`: 일정한 이율과 일정한 납입을 전제로 하는 기본 인수(납입 시점 0/1)를 지원합니다. 불규칙 현금 흐름·IRR/XIRR·RATE는 지원하지 않습니다.
- 내보낸 XLSX는 Excel에서 전체 재계산하도록 설정합니다. 공유·배열 수식은 보존하되 영역 편집은 차단합니다. 앱 계산 결과와 Excel의 결과를 중요한 계산에서 대조하세요.

## 검증 예

조건 합계·와일드카드·정확/근사 조회·오류·거듭제곱 우선순위·유니코드 텍스트·날짜 기준·통계·금융 역산을 자동 검사합니다. 테스트 통과는 모든 가능한 인수 조합이나 모든 문서의 정확성을 보장하지 않습니다.

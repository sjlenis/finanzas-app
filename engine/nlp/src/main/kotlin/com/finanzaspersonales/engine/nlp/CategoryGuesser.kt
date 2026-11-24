package com.finanzaspersonales.engine.nlp

/**
 * Asigna una categoría por palabras clave. Primero busca el nombre exacto de una
 * categoría del usuario (incluidas las que él creó); luego un diccionario colombiano.
 */
internal object CategoryGuesser {

    /** Palabra clave (normalizada) -> nombre de la categoría por defecto. */
    private val keywords: Map<String, List<String>> = mapOf(
        "Alimentación" to listOf(
            "almuerzo", "almorce", "desayuno", "desayune", "comida", "comi", "cena", "comer", "mercado", "mercar",
            "restaurante", "cafe", "tinto", "empanada", "pan", "panaderia", "domicilio", "rappi", "pizza",
            "hamburguesa", "pollo", "fruta", "frutas", "onces", "snack", "mecato", "tienda", "supermercado",
            "d1", "ara", "exito", "olimpica", "carulla", "jugo", "gaseosa", "agua embotellada",
        ),
        "Transporte" to listOf(
            "gasolina", "tanqueo", "tanquee", "bus", "buseta", "taxi", "uber", "didi", "indriver", "transporte",
            "transmilenio", "metro", "mio", "sitp", "peaje", "parqueadero", "parqueo", "pasaje", "pasajes",
            "moto", "mototaxi", "soat", "tecnomecanica", "lavada", "aceite", "llanta",
        ),
        "Vivienda" to listOf("arriendo", "alquiler", "administracion", "hipoteca"),
        "Servicios públicos" to listOf(
            "servicios", "luz", "energia", "agua", "gas", "internet", "plan de celular", "recarga", "telefono",
            "epm", "claro", "movistar", "tigo", "wom", "aseo",
        ),
        "Salud" to listOf(
            "medicina", "medicinas", "medicamento", "medicamentos", "drogueria", "farmacia", "medico", "doctor",
            "eps", "cita", "odontologo", "dentista", "examen", "examenes", "gafas", "terapia",
        ),
        "Educación" to listOf(
            "universidad", "matricula", "semestre", "curso", "colegio", "libro", "libros", "fotocopias",
            "cuadernos", "utiles", "pension", "clases",
        ),
        "Entretenimiento" to listOf(
            "cine", "fiesta", "rumba", "cerveza", "cervezas", "trago", "concierto", "partido", "juego", "videojuego",
            "salida", "paseo", "bar", "discoteca", "boliche", "bolos", "voleibol", "futbol", "gimnasio", "gym",
        ),
        "Ropa y accesorios" to listOf(
            "ropa", "camisa", "camiseta", "pantalon", "jean", "zapatos", "tenis", "chaqueta", "vestido",
            "accesorios", "reloj", "gorra", "medias", "bolso",
        ),
        "Suscripciones" to listOf(
            "netflix", "spotify", "disney", "prime", "youtube premium", "suscripcion", "hbo", "max", "icloud",
            "google one", "chatgpt", "membresia",
        ),
        "Compras personales" to listOf(
            "compras", "celular", "audifonos", "regalo", "cosmeticos", "maquillaje", "perfume", "corte",
            "peluqueria", "barberia", "aseo personal",
        ),
        "Deudas" to listOf("cuota", "tarjeta de credito", "credito", "abono", "prestamo del banco"),
        "Ingresos laborales" to listOf("salario", "sueldo", "nomina", "quincena", "prima", "pago del trabajo"),
        "Ingresos adicionales" to listOf("trabajo", "freelance", "venta", "vendi", "bono", "propina", "comision por"),
    )

    fun guess(text: String, kind: DraftKind, userCategories: List<String>): String? {
        val wantsIncome = kind == DraftKind.INCOME
        // 1. Nombre de una categoría del usuario escrito tal cual (gana la más larga).
        userCategories
            .sortedByDescending { it.length }
            .firstOrNull { text.containsWord(normalize(it)) }
            ?.let { return it }
        // 2. Diccionario, filtrando por tipo: ingresos solo con categorías de ingreso.
        val candidates = keywords.filterKeys { name ->
            val incomeCategory = name.startsWith("Ingresos")
            incomeCategory == wantsIncome
        }
        for ((category, words) in candidates) {
            if (text.containsAnyWord(words)) return category.takeIf { it in userCategories || userCategories.isEmpty() }
        }
        return when (kind) {
            DraftKind.INCOME -> "Ingresos adicionales".takeIf { it in userCategories }
            DraftKind.LOAN_GIVEN, DraftKind.LOAN_RECEIVED -> "Préstamos".takeIf { it in userCategories }
            else -> null
        }
    }
}

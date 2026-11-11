package com.example.sostavslova.data

/** Synthetic wire fixture; deliberately independent of the production schedule and assets. */
internal val miniCampaignJson = """
    {
      "version": 2, "countMinLength": 4,
      "stages": [{"number": 1, "firstLevel": 1, "lastLevel": 2}],
      "levels": [
        {"number": 1, "word": "картотека", "stage": 1, "requiredStars": 2, "tasks": [
          {"id": "count", "type": "T1", "n": 2},
          {"id": "initial", "type": "T2", "letter": "к", "n": 1},
          {"id": "long", "type": "T3", "minLength": 4, "hintTarget": "карта"},
          {"id": "riddle", "type": "T4", "targets": ["кот"]},
          {"id": "ending", "type": "T5", "ending": "ка", "minLength": 3}
        ]},
        {"number": 2, "word": "трактор", "stage": 1, "requiredStars": 1, "tasks": [
          {"id": "theme", "type": "T6", "category": "synthetic", "n": 1, "words": ["рот", "торт"]},
          {"id": "contains", "type": "T7", "letter": "р", "n": 1}
        ]}
      ]
    }
""".trimIndent()

"""Conservative, local thematic evidence for Kaikki senses; no article text leaves the process."""
import json
import re
from pathlib import Path

TAXONOMY_PATH = Path(__file__).with_name("categories.json")

# Closed vocabularies, never substring searches through a definition or a category.
ALIASES = {
    "animals": "животные|млекопитающие|зоология|зоол|zoology|mammals",
    "birds": "птицы|орнитология|орнит|ornithology|birds",
    "fish": "рыбы|водные животные|ихтиология|ихтиол|ichthyology|fishes",
    "insects": "насекомые|паукообразные|энтомология|энтом|entomology|arachnology",
    "plants": "растения|ботаника|ботан|botany",
    "fruits": "фрукты|ягоды", "vegetables": "овощи",
    "food": "еда|пища|продукты питания|food", "drinks": "напитки|beverages",
    "cooking": "кулинария|кулин|cooking", "home": "быт|домашний обиход",
    "furniture": "мебель", "utensils": "посуда", "clothing": "одежда|обувь|clothing",
    "tools": "инструменты", "technology": "техника|техн|technology",
    "transport": "транспорт|transport", "buildings": "здания|сооружения|архитектура|архит|architecture",
    "places": "территории|местности", "nature": "природа", "weather": "погода|метеорология|метеор|meteorology",
    "materials": "материалы|вещества", "body": "анатомия|анат|anatomy",
    "health": "медицина|мед|medicine", "people": "люди", "family": "родство|семья",
    "professions": "профессии", "society": "общество|политика|полит|politics",
    "education": "образование|педагогика|педаг|education", "science": "наука|science",
    "language": "лингвистика|лингв|языкознание|linguistics", "literature": "литература|лит|literature",
    "art": "искусство|искусств|art", "music": "музыка|муз|music",
    "sport": "спорт|игры|спорт|sports|games", "work": "труд|хозяйство",
    "time": "время", "feelings": "чувства|состояния", "actions": "действия|события",
}
# Only immediate hypernyms. Deliberately no scan beyond the start of the gloss.
HEADS = {
    "animals": "животное|млекопитающее|зверь", "birds": "птица",
    "fish": "рыба", "insects": "насекомое|паук", "plants": "растение|дерево|кустарник|трава",
    "fruits": "фрукт|ягода", "vegetables": "овощ", "food": "пища|продукт питания|блюдо",
    "drinks": "напиток", "cooking": "способ приготовления пищи",
    "home": "предмет домашнего обихода", "furniture": "предмет мебели|мебель",
    "utensils": "предмет посуды|посуда", "clothing": "предмет одежды|одежда|обувь",
    "tools": "инструмент", "technology": "устройство|прибор|механизм",
    "transport": "транспортное средство", "buildings": "здание|сооружение",
    "places": "территория|местность", "nature": "природное явление",
    "weather": "атмосферное явление", "materials": "вещество|материал",
    "body": "часть тела|орган тела", "health": "болезнь|заболевание|лекарство",
    "people": "человек", "family": "член семьи|родственник", "professions": "работник|специалист",
    "society": "общественная организация", "education": "учебное заведение|учебный предмет",
    "science": "наука|научная дисциплина", "language": "часть речи|единица языка",
    "literature": "литературное произведение", "art": "произведение искусства",
    "music": "музыкальный инструмент|музыкальное произведение", "sport": "вид спорта|спортивная игра",
    "work": "вид труда", "time": "промежуток времени|единица времени",
    "feelings": "чувство|эмоция", "actions": "действие|событие",
}
PARENTS = {"birds": "animals", "fish": "animals", "insects": "animals",
           "fruits": "plants", "vegetables": "plants", "professions": "people", "family": "people"}
# Non-thematic leading marks may precede a topical mark or a hypernym.
NEUTRAL = {"разг", "прост", "книжн", "перен", "устар", "редк", "спец", "шутл", "неодобр", "собир"}


def normalize(value):
    return " ".join(value.casefold().replace("ё", "е").split()).strip(" .")


def load_taxonomy(path=TAXONOMY_PATH):
    taxonomy = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(taxonomy, dict) or "other" not in taxonomy or not set(ALIASES) <= taxonomy.keys():
        raise ValueError("Некорректный справочник категорий")
    return taxonomy


def aliases(taxonomy):
    return {normalize(label): category for category in ALIASES if category in taxonomy
            for label in [category, taxonomy[category], *ALIASES[category].split("|")]}


def choose(evidence):
    candidates = {category for category, _ in evidence}
    # A specific taxon supersedes its explicit broad parent at the same tier.
    candidates -= {PARENTS[category] for category in candidates if category in PARENTS}
    if len(candidates) != 1:
        return {"category": "other", "category_source": "unknown:conflicting_evidence"}
    category = candidates.pop()
    sources = sorted({source for value, source in evidence if value == category})
    return {"category": category, "category_source": ";".join(sources)}


def classify_sense(sense, taxonomy):
    lookup = aliases(taxonomy)
    evidence = []
    for field in ("categories", "topics"):
        for item in sense.get(field, []):
            label = item.get("name", "") if isinstance(item, dict) else item
            if not isinstance(label, str):
                continue
            label = normalize(re.sub(r"^ru:|/ru$", "", label, flags=re.I))
            category = lookup.get(label)
            if category:
                evidence.append((category, "sense." + field + ":" + label))
    if evidence:
        return choose(evidence)
    for field in ("raw_tags", "tags"):
        for tag in sense.get(field, []):
            if not isinstance(tag, str):
                continue
            for label in re.split(r"[,;]", tag):
                category = lookup.get(normalize(label))
                if category:
                    evidence.append((category, "sense." + field + ":" + normalize(label)))
    if evidence:
        return choose(evidence)
    text = normalize(sense.get("text", ""))
    leading = []
    while True:
        mark = re.match(r"^\(?([а-я-]+)\.\)?\s*", text)
        if not mark:
            break
        label = mark.group(1)
        category = lookup.get(label)
        if category:
            leading.append((category, "leading_label:" + label))
        elif label not in NEUTRAL:
            break
        text = text[mark.end():].lstrip(" ,;")
    if leading:
        return choose(leading)
    # Only a small closed set of modifiers and the immediate head is allowed.
    text = re.sub(r"^(?:(?:небольшой|небольшая|небольшое|крупный|крупная|крупное|многолетнее|однолетнее)\s+){0,2}", "", text)
    for category, heads in HEADS.items():
        if category in taxonomy and re.match(r"^(?:" + heads + r")(?![а-я])", text):
            return {"category": category, "category_source": "gloss_head:" + category}
    return {"category": "other", "category_source": "unknown:no_thematic_evidence"}


def word_category(senses):
    if not senses:
        return {"category": "other", "category_source": "unknown:no_usable_sense"}
    categories = {sense["category"] for sense in senses}
    if "other" in categories:
        reason = "uncertain_sense" if len(senses) > 1 else senses[0]["category_source"].removeprefix("unknown:")
        return {"category": "other", "category_source": "unknown:" + reason}
    if len(categories) != 1:
        return {"category": "other", "category_source": "unknown:ambiguous_senses"}
    return {"category": next(iter(categories)),
            "category_source": ";".join(sorted({sense["category_source"] for sense in senses}))}


def category_index(metadata, taxonomy):
    """Only confident, specific categories may feed T6; unknown/other is never exported as a target."""
    index = {category: [] for category in sorted(taxonomy) if category != "other"}
    for word, value in sorted(metadata.items()):
        category = value["category"]
        if category != "other" and not value["category_source"].startswith("unknown:"):
            index[category].append(word)
    return {"schema": "word-category-index-v1", "categories": index, "word_metadata": dict(sorted(metadata.items()))}

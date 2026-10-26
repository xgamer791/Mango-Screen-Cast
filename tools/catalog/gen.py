import json, re
exec(open('/tmp/catalog/plan.py').read())
tv = json.load(open('/tmp/catalog/tvmaze.json')); tv.update(json.load(open('/tmp/catalog/tvmaze2.json')))
ABOUT = {
 'last_of_us': "Twenty years after a pandemic ended the world, a hardened survivor is hired to smuggle a 14-year-old girl across a ruined America.",
 'the_pitt': "One 15-hour shift in a Pittsburgh trauma center, told hour by hour by the doctors and nurses working it.",
 'shogun': "Japan, 1600: an English pilot washes ashore and is caught between rival lords on the eve of civil war.",
 'andor': "Thief Cassian Andor is drawn into the fight against the Empire in the years before Rogue One.",
 'landman': "Billy Bob Thornton runs crisis control for an oil company in the boomtowns of West Texas.",
 'white_lotus': "A week at an exclusive resort, where the guests and the staff who serve them come undone.",
 'task': "An FBI agent leads a task force against a string of drug-house robberies in the suburbs of Philadelphia.",
 'the_bear': "A fine-dining chef comes home to Chicago to run his late brother's sandwich shop.",
 'only_murders': "Three neighbors who share a true-crime obsession start solving the murders in their own building.",
 'abbott': "Dedicated teachers and a tone-deaf principal keep a Philadelphia public school running against the odds.",
 'hacks': "A legendary Las Vegas comedian and a young comedy writer form an unlikely, combative partnership.",
 'the_office': "A mockumentary about everyday life at the Dunder Mifflin paper company in Scranton.",
 'simpsons': "Homer, Marge, Bart, Lisa and Maggie, in the longest-running scripted series on American TV.",
 'bluey': "A six-year-old Blue Heeler turns everyday family life into big imaginative games.",
 'stranger_things': "A boy vanishes, and a small Indiana town uncovers secret experiments and a terrifying world beneath it.",
 'fallout': "Two hundred years after the bombs, a vault dweller steps out into a wasteland with almost nothing left.",
 'house_of_dragon': "House Targaryen tears itself apart in a civil war, 200 years before Game of Thrones.",
 'mandalorian': "A lone bounty hunter and a mysterious child travel the far reaches of the Star Wars galaxy.",
 'the_boys': "A group of vigilantes takes on corrupt superheroes who abuse their fame and power.",
 'squid_game': "Hundreds of players deep in debt compete in deadly children's games for a fortune.",
 'wednesday': "Wednesday Addams investigates a murder spree while making friends and foes at Nevermore Academy.",
 'reacher': "Former military police investigator Jack Reacher drifts from town to town, and trouble finds him.",
 'tulsa_king': "Sylvester Stallone is a New York mafia capo who, out after 25 years, is exiled to Oklahoma.",
 'dexter': "Dexter Morgan wakes from a coma and heads to New York City to find his son.",
 'adolescence': "A family's world collapses when their 13-year-old son is arrested for the murder of a classmate.",
 'daredevil': "Matt Murdock and Wilson Fisk, now mayor of New York, head for a collision as their pasts resurface.",
 'poker_face': "Charlie Cale can always tell when someone is lying, and every stop on the road brings a new murder.",
 'peacemaker': "John Cena is Peacemaker, a violent vigilante who believes in peace at any cost.",
 'notld': "Strangers barricade themselves in a farmhouse as the dead rise. George A. Romero's 1968 original.",
 'nosferatu': "F. W. Murnau's silent horror classic, with Max Schreck as the vampire Count Orlok.",
 'metropolis': "Fritz Lang's vision of a towering city of the future, divided between its thinkers and its workers.",
 'charade': "Audrey Hepburn and Cary Grant in a Paris caper of a missing fortune, false names and suspense.",
 'girl_friday': "Cary Grant and Rosalind Russell trade rapid-fire lines as a newspaper editor and his star reporter.",
 'safety_last': "Harold Lloyd climbs the outside of a department store and ends up hanging from its clock.",
 'gold_rush': "Charlie Chaplin's Little Tramp heads north to the Klondike in search of gold.",
 'the_general': "Buster Keaton chases the Union spies who stole his locomotive, in one of the great silent comedies.",
 'nbc_nightly': "The evening news from NBC, with the day's top stories at home and abroad.",
 'cbs_evening': "The evening news from CBS News correspondents around the world.",
 'pbs_news': "In-depth news and analysis from PBS every weeknight.",
 'today': "NBC's morning show of news, interviews and features, on the air since 1952.",
 'sixty_minutes': "Investigative reports, interviews and profiles, Sunday nights on CBS since 1968.",
 'snl': "Live sketch comedy and music from New York, Saturday nights since 1975.",
 'the_voice': "Star coaches mentor singers through blind auditions, battles and live shows.",
 'survivor': "Castaways outwit, outplay and outlast each other for the title of Sole Survivor.",
 'jeopardy': "The answers come first, and the contestants reply with the questions.",
 'wheel': "Contestants spin the wheel and solve hidden phrases one letter at a time.",
 'hells_kitchen': "Gordon Ramsay puts aspiring chefs through a brutal culinary competition.",
 'snf': "Primetime NFL football every Sunday night on NBC and Peacock.",
 'hard_knocks': "HBO and NFL Films go behind the scenes of an NFL team's training camp.",
 'drive_to_survive': "Drivers, team bosses and owners fight on and off the track through a season of Formula 1.",
 'full_swing': "Pro golfers chase majors and careers in a season where one shot can change everything.",
 'break_point': "The world's best tennis players chase Grand Slams through a full season.",
 'starting_5': "Five NBA stars, LeBron James among them, through one season on and off the court.",
 'last_dance': "Michael Jordan and the 1990s Chicago Bulls, in the season that ended their dynasty.",
 'wrexham': "Ryan Reynolds and Rob McElhenney buy Wales' oldest football club and try to turn it around.",
}
FILMS = {  # key: (title, year, genre)
 'notld': ('Night of the Living Dead', '1968', 'Horror'), 'nosferatu': ('Nosferatu', '1922', 'Horror'),
 'metropolis': ('Metropolis', '1927', 'Sci-fi'), 'charade': ('Charade', '1963', 'Romance, Thriller'),
 'girl_friday': ('His Girl Friday', '1940', 'Comedy'), 'safety_last': ('Safety Last!', '1923', 'Comedy'),
 'gold_rush': ('The Gold Rush', '1925', 'Comedy'), 'the_general': ('The General', '1926', 'Comedy, Action'),
}
GENRE = {'Science-Fiction': 'Sci-fi', 'Adult': None, 'Children': 'Kids', 'Medical': 'Medical', 'Food': 'Food', 'Espionage': 'Spy'}
NETWORK = {'Syndication': 'local TV'}
def kt(s): return json.dumps(s, ensure_ascii=False)
rows = {}
lines = []
for key, title, page, row, prov, link in ITEMS:
    if key in FILMS:
        name, year, genres = FILMS[key]; kind, years = 'Film', year; on = 'Tubi'
    else:
        s = tv[title]; name = s['name']
        y0 = (s['premiered'] or '')[:4]; y1 = (s['ended'] or '')[:4]
        running = s['status'] in ('Running', 'To Be Determined', 'In Development')
        years = f'Since {y0}' if running or not y1 else (y0 if y0 == y1 else f'{y0}–{y1}')
        kind = {'News': 'News', 'Sports': 'Live sports', 'Talk Show': 'Talk show', 'Game Show': 'Game show',
                'Reality': 'Reality', 'Variety': 'Comedy', 'Documentary': 'Docuseries', 'Animation': 'Series'}.get(s['type'], 'Series')
        if s['type'] == 'Variety': kind = 'Series'
        gs = [GENRE.get(g, g) for g in (s['genres'] or [])]
        gs = [g for g in gs if g and g != 'Sports' or (g == 'Sports' and kind != 'Docuseries')]
        genres = ', '.join(gs[:2])
        on = NETWORK.get(s['net'], s['net']) if prov == 'Live TV' else prov
    genres = {'snf': 'Football', 'hard_knocks': 'Football', 'drive_to_survive': 'Formula 1', 'full_swing': 'Golf',
              'break_point': 'Tennis', 'starting_5': 'Basketball', 'last_dance': 'Basketball', 'wrexham': 'Soccer',
              'nbc_nightly': '', 'today': 'News', 'snl': 'Comedy, Music'}.get(key, genres)
    if name == 'NBC Sunday Night Football': name = 'Sunday Night Football'
    if name == 'PBS News': name = 'PBS News Hour'
    lines.append(f'        Show({kt(key)}, {kt(name)}, {kt(kind)}, {kt(years)}, {kt(genres)}, {kt(on)}, {kt(prov)},\n'
                 f'            {kt(ABOUT[key])},\n'
                 f'            R.drawable.show_{key}, R.drawable.show_{key}_tile, {kt(link) if link else "null"}),')
    rows.setdefault(page, {}).setdefault(row, []).append(key)
pages = []
for page, rs in rows.items():
    rr = ',\n'.join(f'            Shelf({kt(r)}, listOf({", ".join(kt(k) for k in ks)}))' for r, ks in rs.items())
    pages.append(f'        Page.{page.upper()} to listOf(\n{rr},\n        ),')
src = f'''package com.mangoscreencast.tv.browse

import com.mangoscreencast.tv.R

/**
 * The shows and films on the browse pages: real titles from the TV's own
 * streaming apps, their art from TVMaze (shows) and Wikimedia Commons
 * (public-domain films, all free on Tubi), each run through Upscayl's High
 * Fidelity model at 2x and saved as a 2560 x 1440 backdrop and a 512 x 288
 * tile. Generated from the catalog script; descriptions are our own.
 */
object Catalog {{
    val shows: List<Show> = listOf(
{chr(10).join(lines)}
    )

    private val byKey = shows.associateBy {{ it.key }}

    fun show(key: String): Show = byKey.getValue(key)

    /** Each page's rows, in order. */
    val shelves: Map<Page, List<Shelf>> = mapOf(
{chr(10).join(pages)}
    )

    /** Shows whose title has every word of `query` (any case), in catalog order. */
    fun search(query: String): List<Show> {{
        val words = query.lowercase().split(Regex("\\\\s+")).filter {{ it.isNotBlank() }}
        if (words.isEmpty()) return emptyList()
        return shows.filter {{ show -> words.all {{ it in show.title.lowercase() }} }}
    }}
}}
'''
open('/tmp/msc-roku/android-tv/app/src/main/java/com/mangoscreencast/tv/browse/Catalog.kt', 'w').write(src)
print(len(lines), 'shows')

package mg.maniry.doremi.commonUtils


class Changelog(val version: String, val logs: Array<String>)


var changelog = arrayOf(
        Changelog("2.0.0", arrayOf(
                "Natao @ fiteny malagasy avokoa ny soratra rehetra azo nadika",
                "Nafindra toerana ny bokotra sasany",
                "Fahafahana maka sy mizara solfa @ alalan'ny aterineto",
                "Nafidra toerana ny lisitry ny solfa",
                "Fanitsiana madinika samihafa"
        )),


        Changelog("1.0.1", arrayOf(
                "Fanitsiana bugs sy fanatsarana madinika",
                "Mitahiry ny solfa ho .html mba ho azo atao @ taratasy",
                "Mitahiry ny feon-kira .mid mba ho azo vakiana @ application hafa",
                "Nasiana feon-java-maneno maromaro",
                "Isan'andininy"
        )),

        Changelog("1.0.0", arrayOf(
                "Laharana voalohany.",
                "Manoratra, mamindra, mamaky solfa..."
        ))
)

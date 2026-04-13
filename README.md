<p align="center">
    <img src="droidkiss_logo.svg">
</p>
<p align="center">
    A vintage KiSS doll viewer for modern Android.
</p>

## What are KiSS dolls?
KiSS dolls are vintage digital dress up games, first developed in Japan all the way back in the early 90s. They predate Flash, as well as most modern JavaScript standards. The term "KiSS" is an acronym for "Kisekae Set System" ("kisekae"（着せ替え<sub>（きせかえ）</sub>）meaning "to dress up" in Japanese).
### Typical aspects of a KiSS doll
1. The main information of a KiSS doll is stored in a CNF file.
2. Has its own image format (CEL).
3. Has its own palette format (KCF).
4. Has its own scripting language, called "French KiSS" (often called "FKiSS" for short). This is written in the aforementioned CNF. It features all kinds of functions, from press actions, animations, showing and hiding elements... you name it.
5. Multiple cels can be given the same object number, making them "stick" (e.g. the front and back of a T-shirt).
6. Objects are draggable by default. You have to set a "fix" value (usually above 100) to make them non-draggable.
7. A typical KCF file contains 16 or 256 colors. This means that most CEL files are in an indexed format. However, true color is possible (called "Cherry KiSS", or "CKiSS" for short).
8. Audio (WAV, AU, MIDI) is supported.
9. Typically, KiSS dolls are distributed by packaging every relevant file into an LZH file.

As you can see, KiSS mostly uses its own exclusive file types and standards. On top of that, it uses the archaic LZH format for compressing/packaging dolls. As a result, it is not a very accessible format for modern users. I hope to make it slightly more accessible with this app.

### One handy tip for using this app
Long-pressing an object will "unfix" it if not draggable, and "fix" it if draggable. On desktop viewers, this is usually done through right-clicking the object and selecting "Unfix" from a context menu. Obviously, you can't right-click on a phone (and I thought a context menu would be overkill), so I utilized a simple long-press as a substitute.

## Why I created this app
As a child, I was obsessed with KiSS dolls. I didn't know how to do JavaScript yet, and Flash was also "too difficult", so at the time I never bothered with all that and instead stuck with KiSS. And to be fair, the FKiSS scripting language is relatively easy to understand, even for a child. So, in the days before Scratch, I attempted (keyword) to make my own dolls.

Therefore, it is sad to say that I never finished a single doll. Granted, they do take a *lot* of work to create. However, I did manage to join a collab at one point and contribute my own outfit to a doll at age 12. <sub>(If you ever see the name "Hikaru-chan" in a KiSS doll context, that's most likely me.)</sub>

Back to the modern day. While I've long since retired from creating KiSS dolls, I do still have an affinity for dress up games. I've long since moved on to JavaScript for creating them, especially since you can do much more with JavaScript than with FKiSS. But, *playing* with the old KiSS dolls is still a fun pastime to me, and I'd like to "preserve" the hobby in some way, including on modern systems. Not only that, but the idea of playing with your favorite old dolls anywhere you like is a very fun idea to me.

Now, this project is by no means the first viewer for Android. However, the other viewers have either been discontinued or lack some features I consider essential. Most importantly, though: *none of them work on modern 64-bit Android systems*. All of these factors have made me decide to create my own viewer. And thankfully, unlike when I was 12, I now actually know a little bit about programming, making it much less scary to create my own viewer. <sub>Though, I did have to restart like 5 times or so. In fact, I almost gave up; yet, here I am, with an actually functioning app.</sub>

## Known bugs
- Some WAV/AU files will not play, remaining silent instead; this is a limitation of the native Android sound API. Basically, only PCM-encoded WAV/AU files will play. The only way to solve this problem would be to incorporate FFmpeg into the project. Given how huge that library is, I'm not sure yet if I wanna deal with that. Therefore, for now: tough luck, and I'm sorry. (I'm willing to consider FFmpeg again if enough feedback is given in its favor. Do keep the app size in mind, however.)
- Object bounding boxes are currently being calculated from the width and height of only one cel within said object, meaning that sometimes, objects can "clip" weirdly at the edges. One day, I hope to make this calculation object-dependent instead (I've tried before, and it sadly wasn't working out at the time, so for now, this will have to do).

If you encounter any more bugs that I perhaps missed, please feel free to open an [issue](https://github.com/lottev1991/droidkiss/issues).

## FAQ
### Where can I download KiSS dolls?
The main place I recommend to download KiSS dolls from is the old website [OtakuWorld](https://otakuworld.com/kiss). It used to be a paysite at one point, but hasn't been since 2014, meaning you can download everything on there for free nowadays (don't let the "Free KiSS Zone" distract you; it's an artifact of olden times).

There's also [Ephralon's KiSS cafe](https://kisscafe.ephralon.de/dolls.php), which is a place I hung out on a lot back in the day. <sub>And I may or may not have joined a certain KiSS doll collab there when I was 12.</sub>

There's also good ol' [Archive.org](https://archive.org), in both the "main" section as well as the Web Archive. Not only are there plenty of old KiSS doll websites archived, but the "main" archive also contains archived KiSS dolls (both indidivual ones and collections) for download. Take a look, I'd say.

### Does this viewer support every FKiSS action/event?
In short: **No, and it most likely never will**. For one, there are certain features that are rather complicated and/or rarely used (*I* don't even understand what all of them do!). But also, there are some things that are simply very difficult to implement within the context of a phone/tablet.

Take `keypress` and `mousein`/`mouseout` as just a few examples.
The first one creates an event that gets triggered by a press on your keyboard. It's not *impossible* to implement, but I'd have to find a way to incorporate the phone/tablet keyboard into this.
The second two are hover events. Which is something you do with your mouse. Guess what most people don't use on a phone. Yeah, exactly. I guess in theory I could think of a different way to implement this (e.g. similar to how web pages do it), but I'm not yet sure whether I want to do that or not. And these aren't the only ones!

Now, of course, there are still plenty of functions that I'd love to support, but aren't yet at this time. I will work on them as the app matures. Just one example: implementing palette/color changes should definitely be possible down the road.

As for the functions that *are* already supported, I plan to write all that down in a longer dedicated FAQ page down the road, perhaps on a dedicated website.

### Will this viewer support PNG/JPEG/GIF/etc. cels and/or ZIP archives?
**Likely not**. As convenient as these may seem to implement, the truth is that they're almost never used in KiSS dolls. I simply wanted to create a modern Android viewer to view vintage dolls with. I will consider it *only* if there's somehow ever a KiSS doll revival and people want to use more modern solutions to create dolls with. <sub>I mean, JavaScript is right there, but...</sub>

### Will you incorporate FKiSS5/UltraKiSS functionality into the app?
**Maybe, but if I do, likely not everything**. There are some FKiSS5 functions that don't seem too hard to implement (confirm dialogs, for example). But it's not a very high priority at the moment since they're rarely (if ever) used in KiSS dolls.

### How can I make my own KiSS dolls easily?
Simple: **don't!** Yes, that's right - the literal developer of a KiSS doll viewer recommends *against* creating your own KiSS dolls. Just use JavaScript - jQuery UI with Touch Punch, HTML5 game engines, maybe even vanilla JS if you want to be hardcore... *anything* except this old, obscure, crusty format. Trust me, you'll be a lot happier using modern web technology. You can do everything a KiSS doll can do with JavaScript *and much more*. Plus, every modern web browser supports it. There are even ways to make your JavaScript doll run offline in case you're worried about that.

But in case you *really* want to: GIMP can export KiSS CEL files. I'm not going to help you with the rest. Either way, as much as I think you should just use JavaScript, I can't stop you if you really want to do this.

### Will you put this app on Google Play/F-Droid/etc.?
F-Droid is planned on the long term, once DroidKiSS is out of beta state. Google Play, on the other hand, is a hard **no**. Not only does putting your app on Google Play cost money, but there are also a lot of privacy concerns associated with it. Therefore, I prefer to focus on repositories that respect developer privacy (and that are preferrably free of charge, though I consider privacy somewhat more important).

## Acknowledgements
- First of all, I owe a *huge* thanks to William Miles and his fantastic [UltraKiSS](https://github.com/kisekae/ultrakiss) project. <sub>(There you go: a great cross-platform viewer for desktop.)</sub> I've incorporated the LZH extraction code from that project into my own, since nothing else seemed to be working. I would've still been stuck at the doll extraction state if I hadn't incorporated this code (thankfully Java and Kotlin can interoperate, so I had to change very little to nothing). I've licensed DroidKiSS under the GPL 3.0 to ensure compatibility with the used code.
- Some of my own code was written with the help of Google Gemini. I know this is enough to put some people off from the project, in which case... well, there's not much I can do about that. Please do understand though that I wrote plenty of the code by myself, and that everything is 100% human-vetted and human-tested. I mostly used it as a tool to relieve the workload somewhat and to stop myself from getting confused. I don't simply vibecode nonsense, you know. <sub>(As a sidenote, the logo and icons for this project were created 100% by hand, based on a similarly hand-drawn vector that I downloaded from Pixabay. I don't care for AI art and also don't consider it fun to make, so you'll never see me create and/or use it. And this readme wasn't written with AI either. I don't write text with AI.)</sub>
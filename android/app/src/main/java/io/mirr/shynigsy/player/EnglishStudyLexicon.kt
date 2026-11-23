package io.mirr.shynigsy.player

internal data class StudyLexiconEntry(
    val lemma: String,
    val meaning: String,
    val partOfSpeech: String,
    val forms: Set<String> = emptySet(),
)

/**
 * A small, app-authored English learning glossary with Korean representative meanings.
 * This is bundled learning content, not an external dictionary or contextual translation.
 * Forms are explicit spelling variants; overlapping forms between entries are intentional.
 */
internal object EnglishStudyLexicon {
    private val englishSpelling = Regex("[a-z]+(?:[ -][a-z]+)*")

    val words: List<StudyLexiconEntry> = readEntries(
        """
        # lemma | representative Korean meanings | part of speech | comma-separated forms
        accept|받아들이다; 수락하다|동사|accepts,accepted,accepting
        accuse|비난하다; 혐의를 제기하다|동사|accuses,accused,accusing
        achieve|이루다; 달성하다|동사|achieves,achieved,achieving
        act|행동하다; 연기하다|동사|acts,acted,acting
        admit|인정하다; 입장을 허락하다|동사|admits,admitted,admitting
        advise|조언하다|동사|advises,advised,advising
        afford|감당할 여유가 있다|동사|affords,afforded,affording
        agree|동의하다; 합의하다|동사|agrees,agreed,agreeing
        allow|허락하다; 가능하게 하다|동사|allows,allowed,allowing
        answer|대답하다; 응답하다|동사·명사|answers,answered,answering
        apologize|사과하다|동사|apologizes,apologized,apologizing,apologise,apologises,apologised,apologising
        appear|나타나다; 그렇게 보이다|동사|appears,appeared,appearing
        apply|지원하다; 적용하다|동사|applies,applied,applying
        argue|말다툼하다; 주장하다|동사|argues,argued,arguing
        arrange|정리하다; 준비하다|동사|arranges,arranged,arranging
        arrive|도착하다|동사|arrives,arrived,arriving
        ask|묻다; 부탁하다|동사|asks,asked,asking
        attack|공격하다; 공격|동사·명사|attacks,attacked,attacking
        avoid|피하다|동사|avoids,avoided,avoiding
        be|이다; 있다|동사|am,is,are,was,were,been,being
        beat|이기다; 때리다|동사|beats,beaten,beating
        become|되다|동사|becomes,became,becoming
        begin|시작하다|동사|begins,began,begun,beginning
        believe|믿다; 생각하다|동사|believes,believed,believing
        belong|속하다; 소유이다|동사|belongs,belonged,belonging
        bet|내기를 걸다; 확신하다|동사|bets,betted,betting
        bite|물다; 한 입|동사·명사|bites,bit,bitten,biting
        blame|탓하다; 책임을 돌리다|동사|blames,blamed,blaming
        blow|불다; 날리다|동사|blows,blew,blown,blowing
        borrow|빌리다|동사|borrows,borrowed,borrowing
        break|깨뜨리다; 중단하다; 휴식|동사·명사|breaks,broke,broken,breaking
        breathe|숨 쉬다|동사|breathes,breathed,breathing
        bring|가져오다; 데려오다|동사|brings,brought,bringing
        build|짓다; 만들다|동사|builds,built,building
        burn|타다; 태우다|동사|burns,burned,burnt,burning
        buy|사다|동사|buys,bought,buying
        call|부르다; 전화하다; 통화|동사·명사|calls,called,calling
        cancel|취소하다|동사|cancels,canceled,cancelled,canceling,cancelling
        care|신경 쓰다; 돌봄|동사·명사|cares,cared,caring
        carry|나르다; 지니고 다니다|동사|carries,carried,carrying
        catch|잡다; 알아듣다|동사|catches,caught,catching
        cause|일으키다; 원인|동사·명사|causes,caused,causing
        change|바꾸다; 변화; 거스름돈|동사·명사|changes,changed,changing
        check|확인하다; 점검|동사·명사|checks,checked,checking
        choose|고르다; 선택하다|동사|chooses,chose,chosen,choosing
        clean|청소하다; 깨끗한|동사·형용사|cleans,cleaned,cleaning,cleaner,cleanest
        close|닫다; 가까운|동사·형용사|closes,closed,closing,closer,closest
        come|오다|동사|comes,came,coming
        complain|불평하다|동사|complains,complained,complaining
        consider|고려하다; 여기다|동사|considers,considered,considering
        continue|계속하다|동사|continues,continued,continuing
        cook|요리하다; 요리사|동사·명사|cooks,cooked,cooking
        cost|비용이 들다; 비용|동사·명사|costs,costing
        count|세다; 중요하다|동사|counts,counted,counting
        cover|덮다; 다루다; 표지|동사·명사|covers,covered,covering
        create|만들다; 창조하다|동사|creates,created,creating
        cry|울다; 외치다|동사|cries,cried,crying
        cut|자르다; 줄이다|동사|cuts,cutting
        dance|춤추다; 춤|동사·명사|dances,danced,dancing
        deal|다루다; 거래|동사·명사|deals,dealt,dealing
        decide|결정하다|동사|decides,decided,deciding
        deliver|배달하다; 전달하다|동사|delivers,delivered,delivering
        deny|부인하다; 거절하다|동사|denies,denied,denying
        depend|의존하다; 달려 있다|동사|depends,depended,depending
        describe|묘사하다; 설명하다|동사|describes,described,describing
        deserve|받을 만하다|동사|deserves,deserved,deserving
        destroy|파괴하다|동사|destroys,destroyed,destroying
        develop|발전하다; 개발하다|동사|develops,developed,developing
        die|죽다|동사|dies,died,dying
        disappear|사라지다|동사|disappears,disappeared,disappearing
        discover|발견하다|동사|discovers,discovered,discovering
        discuss|논의하다|동사|discusses,discussed,discussing
        do|하다|동사|does,did,done,doing
        doubt|의심하다; 의심|동사·명사|doubts,doubted,doubting
        draw|그리다; 끌어당기다|동사|draws,drew,drawn,drawing
        dream|꿈꾸다; 꿈|동사·명사|dreams,dreamed,dreamt,dreaming
        dress|옷을 입다; 원피스|동사·명사|dresses,dressed,dressing
        drink|마시다; 음료|동사·명사|drinks,drank,drunk,drinking
        drive|운전하다; 몰아가다|동사|drives,drove,driven,driving
        drop|떨어뜨리다; 내려놓다|동사|drops,dropped,dropping
        earn|벌다; 얻다|동사|earns,earned,earning
        eat|먹다|동사|eats,ate,eaten,eating
        encourage|격려하다; 권장하다|동사|encourages,encouraged,encouraging
        end|끝나다; 끝내다; 끝|동사·명사|ends,ended,ending
        enjoy|즐기다|동사|enjoys,enjoyed,enjoying
        enter|들어가다; 입력하다|동사|enters,entered,entering
        escape|탈출하다; 벗어나다|동사|escapes,escaped,escaping
        expect|예상하다; 기대하다|동사|expects,expected,expecting
        explain|설명하다|동사|explains,explained,explaining
        face|마주하다; 얼굴|동사·명사|faces,faced,facing
        fail|실패하다; 하지 못하다|동사|fails,failed,failing
        fall|떨어지다; 넘어지다|동사|falls,fell,fallen,falling
        feed|먹이를 주다; 먹여 살리다|동사|feeds,fed,feeding
        feel|느끼다|동사|feels,felt,feeling
        fight|싸우다; 싸움|동사·명사|fights,fought,fighting
        fill|채우다|동사|fills,filled,filling
        find|찾다; 발견하다|동사|finds,found,finding
        finish|끝내다; 마치다|동사|finishes,finished,finishing
        fit|맞다; 건강한|동사·형용사|fits,fitted,fitting,fitter,fittest
        fix|고치다; 해결하다|동사|fixes,fixed,fixing
        fly|날다; 비행하다|동사|flies,flew,flown,flying
        follow|따라가다; 이해하다|동사|follows,followed,following
        force|강요하다; 힘|동사·명사|forces,forced,forcing
        forget|잊다|동사|forgets,forgot,forgotten,forgetting
        forgive|용서하다|동사|forgives,forgave,forgiven,forgiving
        freeze|얼다; 꼼짝하지 않다|동사|freezes,froze,frozen,freezing
        get|얻다; 도착하다; 되다|동사|gets,got,gotten,getting
        give|주다|동사|gives,gave,given,giving
        go|가다; 진행되다|동사|goes,went,gone,going
        grab|붙잡다; 급히 챙기다|동사|grabs,grabbed,grabbing
        grow|자라다; 키우다|동사|grows,grew,grown,growing
        guess|추측하다; 짐작|동사·명사|guesses,guessed,guessing
        handle|다루다; 처리하다; 손잡이|동사·명사|handles,handled,handling
        hang|걸다; 매달리다|동사|hangs,hung,hanged,hanging
        happen|일어나다; 발생하다|동사|happens,happened,happening
        hate|싫어하다; 미워하다|동사|hates,hated,hating
        have|가지다; 먹다; 겪다|동사|has,had,having
        hear|듣다; 소식을 듣다|동사|hears,heard,hearing
        help|돕다; 도움|동사·명사|helps,helped,helping
        hide|숨기다; 숨다|동사|hides,hid,hidden,hiding
        hit|때리다; 부딪치다|동사|hits,hitting
        hold|잡고 있다; 개최하다|동사|holds,held,holding
        hope|바라다; 희망|동사·명사|hopes,hoped,hoping
        hurt|다치게 하다; 아프다|동사|hurts,hurting
        imagine|상상하다|동사|imagines,imagined,imagining
        improve|나아지다; 개선하다|동사|improves,improved,improving
        include|포함하다|동사|includes,included,including
        insist|고집하다; 주장하다|동사|insists,insisted,insisting
        introduce|소개하다; 도입하다|동사|introduces,introduced,introducing
        invite|초대하다|동사|invites,invited,inviting
        join|참여하다; 합류하다|동사|joins,joined,joining
        joke|농담하다; 농담|동사·명사|jokes,joked,joking
        keep|유지하다; 계속하다; 보관하다|동사|keeps,kept,keeping
        kill|죽이다|동사|kills,killed,killing
        kiss|입 맞추다; 입맞춤|동사·명사|kisses,kissed,kissing
        know|알다|동사|knows,knew,known,knowing
        laugh|웃다; 웃음|동사·명사|laughs,laughed,laughing
        lead|이끌다; 이어지다|동사|leads,led,leading
        learn|배우다; 알게 되다|동사|learns,learned,learnt,learning
        leave|떠나다; 남겨 두다|동사|leaves,left,leaving
        lend|빌려주다|동사|lends,lent,lending
        let|허락하다; 하게 두다|동사|lets,letting
        lie|거짓말하다; 누워 있다|동사|lies,lied,lay,lain,lying
        like|좋아하다; 같은|동사·전치사|likes,liked,liking
        listen|귀 기울여 듣다|동사|listens,listened,listening
        live|살다; 살아 있다|동사|lives,lived,living
        look|보다; 보이다|동사|looks,looked,looking
        lose|잃다; 지다|동사|loses,lost,losing
        love|사랑하다; 사랑|동사·명사|loves,loved,loving
        make|만들다; 하게 하다|동사|makes,made,making
        manage|해내다; 관리하다|동사|manages,managed,managing
        marry|결혼하다|동사|marries,married,marrying
        matter|중요하다; 문제; 사안|동사·명사|matters,mattered,mattering
        mean|뜻하다; 의도하다|동사|means,meant,meaning
        meet|만나다; 충족하다|동사|meets,met,meeting
        mind|꺼리다; 신경 쓰다; 마음|동사·명사|minds,minded,minding
        miss|그리워하다; 놓치다|동사|misses,missed,missing
        move|움직이다; 이사하다|동사|moves,moved,moving
        need|필요하다; 필요|동사·명사|needs,needed,needing
        notice|알아차리다; 안내문|동사·명사|notices,noticed,noticing
        offer|제안하다; 제공하다; 제안|동사·명사|offers,offered,offering
        open|열다; 열린|동사·형용사|opens,opened,opening
        order|주문하다; 명령; 순서|동사·명사|orders,ordered,ordering
        owe|빚지다; 신세 지다|동사|owes,owed,owing
        pass|지나가다; 건네다; 통과하다|동사|passes,passed,passing
        pay|지불하다; 임금|동사·명사|pays,paid,paying
        pick|고르다; 집다|동사|picks,picked,picking
        plan|계획하다; 계획|동사·명사|plans,planned,planning
        play|놀다; 경기하다; 연주하다|동사|plays,played,playing
        prefer|더 좋아하다|동사|prefers,preferred,preferring
        prepare|준비하다|동사|prepares,prepared,preparing
        pretend|인 척하다|동사|pretends,pretended,pretending
        promise|약속하다; 약속|동사·명사|promises,promised,promising
        protect|보호하다|동사|protects,protected,protecting
        prove|증명하다|동사|proves,proved,proven,proving
        pull|당기다|동사|pulls,pulled,pulling
        push|밀다; 재촉하다|동사|pushes,pushed,pushing
        put|놓다; 두다|동사|puts,putting
        quit|그만두다|동사|quits,quitted,quitting
        raise|올리다; 기르다|동사|raises,raised,raising
        reach|도착하다; 닿다; 연락하다|동사|reaches,reached,reaching
        read|읽다|동사|reads,reading
        realize|깨닫다; 실현하다|동사|realizes,realized,realizing,realise,realises,realised,realising
        receive|받다|동사|receives,received,receiving
        recognize|알아보다; 인정하다|동사|recognizes,recognized,recognizing,recognise,recognises,recognised,recognising
        refuse|거절하다|동사|refuses,refused,refusing
        regret|후회하다; 유감|동사·명사|regrets,regretted,regretting
        relax|쉬다; 긴장을 풀다|동사|relaxes,relaxed,relaxing
        remember|기억하다|동사|remembers,remembered,remembering
        remind|생각나게 하다; 상기시키다|동사|reminds,reminded,reminding
        rent|빌리다; 임대료|동사·명사|rents,rented,renting
        repeat|반복하다; 다시 말하다|동사|repeats,repeated,repeating
        reply|대답하다; 답장|동사·명사|replies,replied,replying
        rest|쉬다; 휴식; 나머지|동사·명사|rests,rested,resting
        return|돌아오다; 돌려주다; 반환|동사·명사|returns,returned,returning
        ride|타다; 태워 주기|동사·명사|rides,rode,ridden,riding
        ring|울리다; 전화하다; 반지|동사·명사|rings,rang,rung,ringing
        rise|오르다; 일어나다|동사|rises,rose,risen,rising
        run|달리다; 운영하다|동사|runs,ran,running
        save|구하다; 절약하다; 저장하다|동사|saves,saved,saving
        say|말하다|동사|says,said,saying
        see|보다; 이해하다; 만나다|동사|sees,saw,seen,seeing
        seem|처럼 보이다|동사|seems,seemed,seeming
        sell|팔다|동사|sells,sold,selling
        send|보내다|동사|sends,sent,sending
        serve|제공하다; 대접하다; 복무하다|동사|serves,served,serving
        set|놓다; 설정하다; 한 벌|동사·명사|sets,setting
        share|나누다; 공유하다; 몫|동사·명사|shares,shared,sharing
        shoot|쏘다; 촬영하다|동사|shoots,shot,shooting
        show|보여 주다; 공연|동사·명사|shows,showed,shown,showing
        shut|닫다|동사|shuts,shutting
        sign|서명하다; 표지판; 징후|동사·명사|signs,signed,signing
        sit|앉다|동사|sits,sat,sitting
        sleep|자다; 잠|동사·명사|sleeps,slept,sleeping
        smile|미소 짓다; 미소|동사·명사|smiles,smiled,smiling
        sound|처럼 들리다; 소리|동사·명사|sounds,sounded,sounding
        speak|말하다; 이야기하다|동사|speaks,spoke,spoken,speaking
        spend|쓰다; 시간을 보내다|동사|spends,spent,spending
        stand|서다; 참다|동사|stands,stood,standing
        start|시작하다; 출발하다|동사|starts,started,starting
        stay|머무르다; 그대로 있다|동사|stays,stayed,staying
        steal|훔치다|동사|steals,stole,stolen,stealing
        stick|붙이다; 달라붙다; 막대기|동사·명사|sticks,stuck,sticking
        stop|멈추다; 그만두다; 정류장|동사·명사|stops,stopped,stopping
        study|공부하다; 연구; 서재|동사·명사|studies,studied,studying
        suggest|제안하다; 시사하다|동사|suggests,suggested,suggesting
        support|지지하다; 지원; 부양|동사·명사|supports,supported,supporting
        suppose|추측하다; 가정하다|동사|supposes,supposed,supposing
        surprise|놀라게 하다; 뜻밖의 일|동사·명사|surprises,surprised,surprising
        survive|살아남다; 견뎌 내다|동사|survives,survived,surviving
        take|가져가다; 데려가다; 걸리다|동사|takes,took,taken,taking
        talk|이야기하다; 대화|동사·명사|talks,talked,talking
        teach|가르치다|동사|teaches,taught,teaching
        tell|말해 주다; 구별하다|동사|tells,told,telling
        thank|감사하다|동사|thanks,thanked,thanking
        think|생각하다|동사|thinks,thought,thinking
        throw|던지다|동사|throws,threw,thrown,throwing
        touch|만지다; 감동시키다; 접촉|동사·명사|touches,touched,touching
        travel|여행하다; 이동하다|동사|travels,traveled,travelled,traveling,travelling
        treat|대하다; 치료하다; 한턱내다|동사|treats,treated,treating
        trust|믿다; 신뢰|동사·명사|trusts,trusted,trusting
        try|시도하다; 노력하다|동사|tries,tried,trying
        turn|돌다; 바꾸다; 차례|동사·명사|turns,turned,turning
        understand|이해하다|동사|understands,understood,understanding
        use|쓰다; 사용|동사·명사|uses,used,using
        visit|방문하다; 방문|동사·명사|visits,visited,visiting
        wait|기다리다|동사|waits,waited,waiting
        wake|깨다; 깨우다|동사|wakes,woke,woken,waking
        walk|걷다; 산책|동사·명사|walks,walked,walking
        want|원하다|동사|wants,wanted,wanting
        warn|경고하다|동사|warns,warned,warning
        watch|지켜보다; 시계|동사·명사|watches,watched,watching
        wear|입다; 착용하다|동사|wears,wore,worn,wearing
        win|이기다; 얻다|동사|wins,won,winning
        wish|바라다; 소원|동사·명사|wishes,wished,wishing
        wonder|궁금해하다; 놀라움|동사·명사|wonders,wondered,wondering
        work|일하다; 작동하다; 일|동사·명사|works,worked,working
        worry|걱정하다; 걱정|동사·명사|worries,worried,worrying
        write|쓰다; 작성하다|동사|writes,wrote,written,writing

        # People, daily life, places, and common story vocabulary
        accident|사고; 우연|명사|accidents
        address|주소; 연설|명사|addresses
        advice|조언|명사|
        age|나이; 시대|명사|ages
        airport|공항|명사|airports
        apartment|아파트; 주거용 세대|명사|apartments
        appointment|약속; 예약; 임명|명사|appointments
        attention|주의; 관심|명사|
        bag|가방; 봉지|명사|bags
        bank|은행; 강둑|명사|banks
        bathroom|욕실; 화장실|명사|bathrooms
        bed|침대|명사|beds
        bill|청구서; 지폐; 법안|명사|bills
        blood|피; 혈액|명사|
        body|몸; 시신|명사|bodies
        book|책|명사|books
        boss|상사; 책임자|명사|bosses
        boy|남자아이; 소년|명사|boys
        boyfriend|남자 친구; 남성 연인|명사|boyfriends
        brother|남자 형제|명사|brothers
        business|사업; 업무; 용무|명사|businesses
        car|자동차|명사|cars
        case|경우; 사건; 상자|명사|cases
        chance|기회; 가능성; 우연|명사|chances
        child|아이; 자녀|명사|children
        choice|선택|명사|choices
        city|도시|명사|cities
        clothes|옷; 의류|명사|
        coffee|커피|명사|coffees
        company|회사; 동행; 함께 있음|명사|companies
        conversation|대화|명사|conversations
        country|나라; 시골|명사|countries
        couple|둘; 한 쌍; 부부|명사|couples
        crime|범죄|명사|crimes
        daughter|딸|명사|daughters
        day|날; 낮|명사|days
        death|죽음|명사|deaths
        decision|결정|명사|decisions
        difference|차이|명사|differences
        dinner|저녁 식사; 만찬|명사|dinners
        doctor|의사; 박사|명사|doctors
        door|문|명사|doors
        emergency|비상사태; 응급 상황|명사|emergencies
        enemy|적|명사|enemies
        evening|저녁|명사|evenings
        evidence|증거|명사|
        excuse|핑계; 변명|명사|excuses
        experience|경험; 경력|명사|experiences
        eye|눈|명사|eyes
        family|가족|명사|families
        father|아버지|명사|fathers
        fault|잘못; 책임; 결함|명사|faults
        fear|두려움|명사|fears
        fire|불; 화재|명사|fires
        floor|바닥; 층|명사|floors
        food|음식|명사|foods
        friend|친구|명사|friends
        future|미래|명사|futures
        game|게임; 경기|명사|games
        girl|여자아이; 소녀|명사|girls
        girlfriend|여자 친구; 여성 연인|명사|girlfriends
        government|정부|명사|governments
        gun|총|명사|guns
        guy|남자; 사람|명사|guys
        hair|머리카락; 털|명사|hairs
        hand|손; 도움|명사|hands
        head|머리; 책임자|명사|heads
        heart|심장; 마음|명사|hearts
        home|집; 가정; 고향|명사|homes
        hospital|병원|명사|hospitals
        hotel|호텔|명사|hotels
        hour|시간; 한 시간|명사|hours
        house|집; 주택|명사|houses
        husband|남편|명사|husbands
        idea|생각; 발상|명사|ideas
        information|정보|명사|
        job|일; 직장; 맡은 일|명사|jobs
        key|열쇠; 핵심|명사|keys
        kid|아이|명사|kids
        kitchen|주방|명사|kitchens
        language|언어|명사|languages
        law|법|명사|laws
        lawyer|변호사|명사|lawyers
        life|삶; 생명; 생활|명사|lives
        light|빛; 조명|명사|lights
        line|줄; 선; 대사|명사|lines
        luck|운; 행운|명사|
        man|남자; 사람|명사|men
        memory|기억; 추억|명사|memories
        message|메시지; 전갈|명사|messages
        minute|분; 잠깐|명사|minutes
        mistake|실수; 잘못|명사|mistakes
        moment|순간; 잠깐|명사|moments
        money|돈|명사|
        month|달; 개월|명사|months
        morning|아침|명사|mornings
        mother|어머니|명사|mothers
        movie|영화|명사|movies
        name|이름|명사|names
        neighbor|이웃|명사|neighbors,neighbour,neighbours
        news|소식; 뉴스|명사|
        night|밤|명사|nights
        number|숫자; 번호; 수|명사|numbers
        office|사무실; 직책|명사|offices
        officer|경찰관; 장교; 임원|명사|officers
        opportunity|기회|명사|opportunities
        pain|통증; 고통|명사|pains
        parent|부모|명사|parents
        part|부분; 역할|명사|parts
        partner|동료; 짝; 배우자|명사|partners
        party|파티; 정당; 일행|명사|parties
        person|사람|명사|people,persons
        phone|전화; 전화기|명사|phones
        picture|사진; 그림|명사|pictures
        place|장소; 자리|명사|places
        point|요점; 지점; 점수|명사|points
        police|경찰|명사|
        power|힘; 권력; 전력|명사|powers
        price|가격; 대가|명사|prices
        problem|문제|명사|problems
        question|질문; 문제|명사|questions
        reason|이유; 이성|명사|reasons
        relationship|관계|명사|relationships
        restaurant|식당|명사|restaurants
        result|결과|명사|results
        road|도로; 길|명사|roads
        room|방; 공간; 여지|명사|rooms
        rule|규칙; 지배|명사|rules
        school|학교|명사|schools
        secret|비밀|명사|secrets
        service|서비스; 응대; 근무|명사|services
        side|옆; 측면; 편|명사|sides
        sister|여자 형제|명사|sisters
        situation|상황|명사|situations
        son|아들|명사|sons
        story|이야기; 기사; 층|명사|stories
        street|거리; 길|명사|streets
        stuff|물건들; 일; 것|명사|
        team|팀; 조|명사|teams
        thing|것; 물건; 일|명사|things
        time|시간; 때; 횟수|명사|times
        town|도시; 동네|명사|towns
        trouble|곤란; 문제|명사|troubles
        truth|진실; 사실|명사|truths
        voice|목소리; 의견|명사|voices
        water|물|명사|waters
        way|방법; 길; 방향|명사|ways
        week|주; 일주일|명사|weeks
        wife|아내|명사|wives
        woman|여자; 여성|명사|women
        word|단어; 말; 약속|명사|words
        world|세계; 세상|명사|worlds
        year|해; 년|명사|years

        # Useful descriptions and conversational adverbs
        able|할 수 있는|형용사|
        afraid|두려워하는; 걱정하는|형용사|
        alone|혼자인; 홀로|형용사·부사|
        amazing|놀라운; 멋진|형용사|
        angry|화난|형용사|angrier,angriest
        available|이용 가능한; 시간이 되는|형용사|
        awful|끔찍한; 매우 나쁜|형용사|
        bad|나쁜; 좋지 않은|형용사|worse,worst
        beautiful|아름다운|형용사|
        big|큰|형용사|bigger,biggest
        busy|바쁜|형용사|busier,busiest
        careful|조심하는; 신중한|형용사|
        cheap|값싼|형용사|cheaper,cheapest
        clear|분명한; 맑은|형용사|clearer,clearest
        cold|추운; 차가운; 감기|형용사·명사|colder,coldest,colds
        comfortable|편안한|형용사|
        crazy|제정신이 아닌; 열광하는|형용사|crazier,craziest
        dangerous|위험한|형용사|
        dead|죽은; 작동하지 않는|형용사|
        different|다른|형용사|
        difficult|어려운|형용사|
        early|이른; 일찍|형용사·부사|earlier,earliest
        easy|쉬운; 편한|형용사|easier,easiest
        empty|빈; 비어 있는|형용사|emptier,emptiest
        enough|충분한; 충분히|형용사·부사|
        excited|신이 난; 흥분한|형용사|
        expensive|비싼|형용사|
        fair|공평한; 꽤 괜찮은|형용사|fairer,fairest
        far|먼; 멀리|형용사·부사|farther,farthest,further,furthest
        fast|빠른; 빨리|형용사·부사|faster,fastest
        fine|괜찮은; 훌륭한; 벌금|형용사·명사|finer,finest,fines
        free|자유로운; 무료의; 한가한|형용사|freer,freest
        full|가득 찬; 배부른|형용사|fuller,fullest
        funny|웃기는; 이상한|형용사|funnier,funniest
        glad|기쁜; 다행인|형용사|gladder,gladdest
        good|좋은; 잘하는|형용사|better,best
        great|훌륭한; 대단한; 큰|형용사|greater,greatest
        guilty|유죄인; 죄책감이 드는|형용사|guiltier,guiltiest
        happy|행복한; 기쁜|형용사|happier,happiest
        hard|어려운; 단단한; 열심히|형용사·부사|harder,hardest
        healthy|건강한|형용사|healthier,healthiest
        honest|정직한; 솔직한|형용사|
        hot|뜨거운; 더운|형용사|hotter,hottest
        hungry|배고픈|형용사|hungrier,hungriest
        important|중요한|형용사|
        innocent|무죄인; 순진한|형용사|
        interesting|흥미로운|형용사|
        kind|친절한; 종류|형용사·명사|kinder,kindest,kinds
        late|늦은; 늦게|형용사·부사|later,latest
        little|작은; 적은; 조금|형용사·부사|less,least
        long|긴; 오래|형용사·부사|longer,longest
        lucky|운이 좋은|형용사|luckier,luckiest
        mad|화난; 제정신이 아닌|형용사|madder,maddest
        married|결혼한|형용사|
        necessary|필요한|형용사|
        nervous|긴장한; 불안한|형용사|
        new|새로운|형용사|newer,newest
        nice|좋은; 친절한|형용사|nicer,nicest
        normal|평범한; 정상적인|형용사|
        old|오래된; 나이가 든|형용사|older,oldest,elder,eldest
        perfect|완벽한; 꼭 알맞은|형용사|
        poor|가난한; 불쌍한; 좋지 못한|형용사|poorer,poorest
        possible|가능한|형용사|
        pretty|예쁜; 꽤|형용사·부사|prettier,prettiest
        proud|자랑스러워하는|형용사|prouder,proudest
        quiet|조용한|형용사|quieter,quietest
        ready|준비된; 기꺼이 하는|형용사|readier,readiest
        real|진짜의; 실제의|형용사|
        rich|부유한; 풍부한|형용사|richer,richest
        right|옳은; 오른쪽의; 바로|형용사·부사|
        sad|슬픈|형용사|sadder,saddest
        safe|안전한|형용사|safer,safest
        serious|심각한; 진지한|형용사|
        short|짧은; 키가 작은; 부족한|형용사|shorter,shortest
        sick|아픈; 싫증이 난|형용사|sicker,sickest
        simple|간단한; 단순한|형용사|simpler,simplest
        single|단 하나의; 미혼인|형용사|
        slow|느린|형용사|slower,slowest
        small|작은|형용사|smaller,smallest
        smart|똑똑한; 세련된|형용사|smarter,smartest
        sorry|미안한; 유감스러운|형용사|sorrier,sorriest
        special|특별한|형용사|
        strange|이상한; 낯선|형용사|stranger,strangest
        strong|강한; 튼튼한|형용사|stronger,strongest
        stupid|어리석은; 멍청한|형용사|stupider,stupidest
        sure|확신하는; 틀림없는|형용사|surer,surest
        sweet|단; 다정한|형용사|sweeter,sweetest
        terrible|끔찍한; 형편없는|형용사|
        tired|피곤한; 싫증이 난|형용사|
        true|사실인; 진실한|형용사|truer,truest
        upset|속상한; 화난|형용사|
        warm|따뜻한|형용사|warmer,warmest
        weak|약한|형용사|weaker,weakest
        weird|이상한; 기묘한|형용사|weirder,weirdest
        wonderful|아주 좋은; 멋진|형용사|
        wrong|틀린; 잘못된|형용사|
        young|어린; 젊은|형용사|younger,youngest
        actually|사실은; 실제로|부사|
        again|다시; 또|부사|
        almost|거의|부사|
        already|이미; 벌써|부사|
        always|항상; 언제나|부사|
        anyway|어쨌든; 그건 그렇고|부사|
        away|떨어져; 멀리|부사|
        definitely|분명히; 확실히|부사|
        especially|특히|부사|
        even|심지어; 조차|부사|
        ever|언젠가; 한 번이라도|부사|
        exactly|정확히; 바로|부사|
        finally|마침내; 마지막으로|부사|
        hardly|거의 안; 도저히 안|부사|
        instead|대신에|부사|
        just|방금; 단지; 딱|부사|
        maybe|아마; 어쩌면|부사|
        never|한 번도 안; 절대로 안|부사|
        often|자주|부사|
        probably|아마도|부사|
        quite|꽤; 아주|부사|
        really|정말; 실제로|부사|
        recently|최근에|부사|
        sometimes|때때로; 가끔|부사|
        soon|곧|부사|sooner,soonest
        still|아직; 여전히|부사|
        suddenly|갑자기|부사|
        together|함께; 같이|부사|
        tomorrow|내일|명사·부사|
        usually|보통; 대개|부사|
        yet|아직; 벌써|부사|
        yesterday|어제|명사·부사|
        """,
    )

    val phrases: List<StudyLexiconEntry> = readEntries(
        """
        # Phrase forms preserve the complete surface phrase for matching.
        a little|조금; 약간|표현|
        a lot of|많은|표현|lots of
        all right|괜찮은; 좋아; 알겠어|표현|alright
        as soon as|하자마자|접속 표현|
        at least|적어도; 최소한|부사구|
        at all|조금이라도; 전혀 (부정문에서)|부사구|
        be able to|할 수 있다|동사구|am able to,is able to,are able to,was able to,were able to,been able to,being able to
        be about to|막 하려던 참이다|동사구|am about to,is about to,are about to,was about to,were about to,been about to
        be supposed to|하기로 되어 있다; 해야 한다|동사구|am supposed to,is supposed to,are supposed to,was supposed to,were supposed to,been supposed to
        because of|때문에|전치사구|
        break down|고장 나다; 무너지다; 분석하다|동사구|breaks down,broke down,broken down,breaking down
        by the way|그런데; 그건 그렇고|표현|
        calm down|진정하다; 진정시키다|동사구|calms down,calmed down,calming down
        come back|돌아오다|동사구|comes back,came back,coming back
        come on|어서; 제발; 말도 안 돼|표현|
        deal with|다루다; 처리하다|동사구|deals with,dealt with,dealing with
        end up|결국 하게 되다; 결국 처하게 되다|동사구|ends up,ended up,ending up
        figure out|알아내다; 이해하다|동사구|figures out,figured out,figuring out
        find out|알아내다; 알게 되다|동사구|finds out,found out,finding out
        get along|잘 지내다; 사이좋게 지내다|동사구|gets along,got along,gotten along,getting along
        get back|돌아오다; 되찾다|동사구|gets back,got back,gotten back,getting back
        get out|나가다; 빠져나오다|동사구|gets out,got out,gotten out,getting out
        get over|극복하다; 회복하다|동사구|gets over,got over,gotten over,getting over
        give up|포기하다; 그만두다|동사구|gives up,gave up,given up,giving up
        go ahead|먼저 하다; 진행하다; 그렇게 하세요|동사구|goes ahead,went ahead,gone ahead,going ahead
        grow up|자라다; 철들다|동사구|grows up,grew up,grown up,growing up
        hang on|잠깐 기다리다; 붙잡고 버티다|동사구|hangs on,hung on,hanging on
        have to|해야 한다|동사구|has to,had to,having to
        in case|경우에 대비해서; 만약에|접속 표현|
        in fact|사실은; 실제로|부사구|
        kind of|좀; 어느 정도; 일종의|표현|
        look after|돌보다|동사구|looks after,looked after,looking after
        look for|찾다|동사구|looks for,looked for,looking for
        look forward to|기대하다; 고대하다|동사구|looks forward to,looked forward to,looking forward to
        make sure|확인하다; 반드시 하다|동사구|makes sure,made sure,making sure
        never mind|신경 쓰지 마; 괜찮아|표현|
        of course|물론; 당연히|표현|
        on purpose|일부러; 고의로|부사구|
        pick up|집어 들다; 데리러 가다; 익히다|동사구|picks up,picked up,picking up
        put off|미루다; 꺼리게 하다|동사구|puts off,putting off
        right now|지금 당장; 바로 지금|부사구|
        run out of|다 써 버리다; 바닥나다|동사구|runs out of,ran out of,running out of
        show up|나타나다; 도착하다|동사구|shows up,showed up,shown up,showing up
        take care of|돌보다; 처리하다|동사구|takes care of,took care of,taken care of,taking care of
        take off|벗다; 이륙하다|동사구|takes off,took off,taken off,taking off
        turn out|결국 드러나다; 판명되다|동사구|turns out,turned out,turning out
        used to|예전에 하곤 했다; 예전에는 이었다|표현|
        work out|운동하다; 해결하다; 잘 풀리다|동사구|works out,worked out,working out
        """,
    )

    private fun readEntries(table: String): List<StudyLexiconEntry> {
        val seen = mutableSetOf<String>()
        return table.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { row ->
                val columns = row.split('|').map(String::trim)
                require(columns.size == 4) { "Study glossary row must contain exactly four fields" }
                val (lemma, meaning, partOfSpeech, formList) = columns
                require(englishSpelling.matches(lemma)) { "Invalid study glossary spelling: $lemma" }
                require(seen.add(lemma)) { "Duplicate study glossary entry: $lemma" }
                require(meaning.isNotBlank() && partOfSpeech.isNotBlank()) {
                    "Missing study glossary description: $lemma"
                }
                val forms = if (formList.isEmpty()) emptyList() else formList.split(',').map(String::trim)
                require(forms.all(englishSpelling::matches)) { "Invalid study glossary form: $lemma" }
                require(forms.size == forms.toSet().size && lemma !in forms) {
                    "Duplicate study glossary form: $lemma"
                }
                StudyLexiconEntry(lemma, meaning, partOfSpeech, forms.toSet())
            }.toList()
    }
}

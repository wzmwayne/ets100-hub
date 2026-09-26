# 单词PK（Word Battle）模块重新解析 v2

> 基于 safe9 去壳后的真实 11-dex 反编译（jadx 1.5.6）复核，替代此前基于不完整 dex 的 v1 报告。
> 源码：analysis/wordpk_src/（21 个关键类）

## 0. 代码分布（去壳后）

| dex | 内容 |
|---|---|
| dex_out_4 | 玩法 UI：syncword/ui/fragment/*、syncword/ui/pk/*、syncword/viewmodel/* |
| dex_out_2 | 数据/网络：syncword/request/*、syncword/bean/* |
| dex_out_10 | 通用 bean：secondary/model/bean/word/*（WordBattleMatchBody/Score/MyWordBattleInfo…） |

包结构：com/ets100/syncword/{bean,request,viewmodel,ui/fragment,ui/pk(internal+info),ui/rank,databinding,event,listener,uitls,widget}。

## 1. 入口与导航

- 主容器：com.ets100.syncword.ui.pk.WordBattleActivity（activity_word_battle.xml，内含 fcv_word_match = NavHostFragment）。
- 导航图：res/navigation/nav_word_battle_match.xml，startDestination = matchFragment：

| destination id | Fragment |
|---|---|
| matchFragment | ui/fragment/WordBattleMatchFragment |
| matchSuccessfulFragment | ui/fragment/WordBattleMatchSuccessFragment（action actionGoBattle → battleFragment） |
| matchFailFragment | ui/fragment/WordBattleMatchFailFragment |
| battleFragment | ui/fragment/WordBattleFragment（核心答题） |
| battleDetailFragment | ui/fragment/WordBattleDetailFragment（详情/排行） |

- 其它 Activity（manifest 已确认）：
  WordBattleInfoActivity、WordBattleEventActivity、WordBattleEventNewActivity（exported）、
  WordBattleCompetitionActivity、WordBattleGroupSelectActivity、BattleSelectAreaActivity、
  PKEventWebActivity、ui/rank/WordBattleRankActivity、ui/rank/WordBattleEventRankActivity。

## 2. WordBattleActivity 逻辑（已确认）

B() 读取 Intent extras 并写入 WordBattleViewModel：

k_homework_bean, extra_is_exam, extra_id, extra_res_id, extra_unit_id,
extra_source, extra_word_source, extra_unit_code, extra_school_level,
extra_today_count, extra_today_limit_count, extra_total_count,
extra_total_limit_count, extra_reward_date, extra_is_competition,
extra_word_ids, extra_competition_group_id, extra_competition_id,
extra_competition_type, extra_data, extra_title

- 用户名截前 4 位作为 j（左上显示名），k 保存用于标题栏。
- u0(boolean) 装配 NavController：
  - z0()==false → 直接进 matchFragment；
  - z0()==true（已带匹配数据，如从竞赛/事件进入）→ 从 savedInstanceState 取 extra_data(词表)/extra_data1(struct)/extra_data2(WordBattleMatchBody)，
    写入 S().setValue(Resource.Success(matchBody)) 并 B0(list,struct)，然后：z==true ? matchSuccessfulFragment : battleDetailFragment；
    数据空则 l8.N() + h0(...) + finish。
- onSaveInstanceState：bundle 超过 400KB 时 clear() 防 TransactionTooLarge。

## 3. 匹配流程（WordBattleMatchFragment）

- t()：显示标题栏；调用 WordBattleViewModel.p0(bookId=extra, userName, 空list) → o0() → SyncWordRepository.getWordList。
- x() 观察 S()（LiveData<Resource<WordBattleMatchBody>>）：
  - Success：y("match success")，给头像加点击动画（延迟 2000ms）→ navController.s(R.id.matchSuccessfulFragment)；
  - DataError code==170007：Toast str_no_data_unit_words，记录日志，→ matchFailFragment；
  - NetException：→ matchFailFragment。
- WordBattleMatchSuccessFragment：播放成功动画，actionGoBattle 进 battleFragment。
- WordBattleMatchFailFragment：重试/返回。

## 4. 核心玩法（WordBattleFragment，1192 行）

关键字段：f/g = 两个 MediaPlayer；h = 选项 Adapter；o = 退出确认 Dialog；l = 我的分数累加。

- s0()：把当前题目的单词/音标渲染到 tv_word_content / tv_symbol，并刷新双方分数。
- n0(int i, View)：选项点击/超时处理。
  - 从 b0().t0()（List<SyncWordStructBean>）按 b0().O() 取当前题，正确答案 = question[0].answer；
  - 选项位置 i 语义：-1 超时、0 答错、>0 答对；
  - 答对 l += 10；把 HomeworkScoreWordBean 写入对应 SyncWordListWordBean 的 myScoreDetail/otherScoreDetail；
  - Toast 文案：答对绿底 str_answer_correct/str_add_ten_points + 连题提示；答错红底 str_tips_wrong_answer；超时灰底 str_tips_answer_timeout；
  - 反馈音：word_pk_feedback_good/bad/win/lose/draw.mp3；BGM word_pk_bgm.mp3；开关 sp_pk_audio_open。
- a0(int result, int addPoint)：结果 → Triple<iconRes, title, tip>
  - -1 负（ic_battle_lose / str_battle_lose）
  - 0 平（ic_battle_finish / str_congratulation / str_tips_battle_tie*）
  - 其它 胜（ic_battle_finish / str_congratulation / str_tips_battle_win*）
- 结算：showBattleResultDialog / showSubmitFailDialog / showSubmitOverTimeDialog；退出二次确认 dialogExit；
  observeSubmitResult / observeSubmitWorkResult 监听提交结果。

## 5. ViewModel（WordBattleViewModel，1006 行）

- 持有 SyncWordRepository g。
- 数据：List<HomeworkScoreWordBean> h、List<SyncWordStructBean> x、List<SyncWordListWordBean> y、List<Integer> z、词/题 id 串 t/u。
- LiveData：
  - D = Resource<WordBattleMatchBody>（匹配/开战）
  - B = Resource<WordBattleSubmitBody>（提交结果）
  - C = Resource<HomeworkScoreSyncRes>（作业同步）
- 计分 E(boolean mine) 返回 Triple<答对题数, 正确率, 用时>：
  - 逐题看 myScoreDetail/otherScoreDetail.point > 0 计一题，每题 +10；
  - 正确率 = round(总分 / (题数*10) * 100)；
  - 用时：我方取本地 m（或 mine.workScoreList[0].useTime），对方取 Opponent.score.use_time。
- B0(wordList, structList)：过滤仅 EnToCn（非竞赛时）或 EnToCn|CnToEn（竞赛 k=true），
  打乱 struct，生成 wordIds（id_ 拼接）与 questionIds（question.id_ 拼接），同步 itemIds/questionItemIds；
  BC 来源时置 bcSource=1, bcUnitCode=unitCode。
- F(list,unitCode)：拉取 SyncWordResourceHelper.o(distinct itemIds) 词结构，配套校验，再 d1(...) 开战；
  空/不匹配 → A0(170007,"") 或 A0(-1,...)。
- 提交：z(point,answer,wordId,questionId) 构造 HomeworkScoreWordBean；submitWordBattle 走 serial_id+score_detail+use_time+source。

## 6. 网络接口（Retrofit，已确认）

客户端：SyncWordEtsClient；接口：SyncWordEtsService、SyncWordEtsHfService。

### SyncWordEtsService（POST，Body Map）
g/pk/start-word-pk            startWordBattle      -> WordBattleMatchBody
g/pk/submit-word              submitWordBattle     -> WordBattleSubmitBody
g/pk/count                    getWordBattleCount   -> WordBattleCountBody
g/pk/summary-word             getUserBattleInfo    -> WordBattleInfoBody
g/pk/ranklist                 getWordBattleRankList
g/pk/activity-config          getWordBattleEventInfo -> WordBattleEvent
g/pk/activity-rank            getWordBattleEventRankList
g/pk/activity-zone            getPKEventArea
g/pk/report-activity-zone     reportPkEventArea    (key=pk_king, level)
g/pk/get-credentials          getCredentials       -> PkCredentials
g/homework/pk-info            getPKHomeworkInfo    -> WordBattleMatchBody
g/homework/score-detail       getScoreDetail
g/homework/score-sync         submitScoreDetail    -> HomeworkScoreSyncRes
g/word/is-in-notebook, g/set/*, g/word/* ...

### SyncWordEtsHfService（竞赛专区）
competition/CompetitionPKStart        competitionPKStart -> PKCompetitionStartBean
competition/competitionPKReport       competitionPKReport
competition/competitionPKDetail       getCompetitionPKDetail -> PKCompetitionDetailBean
competition/competitionPKSignUpCheck  competitionPKSignUpCheck -> CompetitionCheckBean
competition/competitionSignUp         competitionSignUp

### 请求字段（startWordBattle）
unit_id, book_id, word_ids, question_ids, name, source, version=1；
当 wordSource==1 追加：word_source, unit_code, item_ids[], question_item_ids[]。

### 请求字段（submitWordBattle）
serial_id, use_time, source, score_detail:[{point, answer, word_id, question_id}...]。
返回 WordBattleSubmitBody{result, addBean, overtime, sn, point}，overtime==1 为超时。

### 请求字段（competitionPKStart）
name, groupId, wordIds, questionIds（+ 内部扩展字段）。
competitionPKReport：snCode, extend 等。

## 7. 关键数据结构

- WordBattleMatchBody：{serialId, opponent, mine:MyWordBattleInfo, source}（Serializable）。
- MyWordBattleInfo：含 workScoreList:List<HomeworkScoreDetailBean>、用户名等。
- Opponent：含 score:WordBattleScore、workScoreList。
- WordBattleScore：{point, detail:List<HomeworkScoreWordBean>（SerializedName "detail"）, use_time}。
- WordBattleSubmitBody：见上。
- WordBattleInfoBody：bean/pkCount/winCount/winRate/masteredWordNum/usetime/round_count_today/round_count_activity/name。
- WordBattleCountBody：次数限制相关（今日/活动）。
- 选项/题目：SyncWordStructBean{id, question:[SyncWordQuestionBean{id,answer,questionItemId,typeEnToCn,typeCnToEn}], bcItemId}；SyncWordOptionBean{optionName}。

## 8. 值得注意的实现点

1. 强依赖 com.fort.andjni.JniLib：WordBattleMatchBody、WordBattleScore 等 bean 的构造/equals/hashCode/toString 都通过 cV/cZ/cI/cL 反射桥实现，
   由 libdexjni.so 提供。去壳构建必须保留 libdexjni.so（只删 libDexHelper*）。
2. 去推送后不影响 PK：PK 走 SyncWordEtsService，与推送渠道无关。
3. 170007 = 本单元无可用单词，匹配失败页文案 str_no_data_unit_words。
4. 分数完全由本地逐题计算 + 服务端根据 serial_id 结算；正确答案来自题目数据（question[0].answer），客户端只上报 point/answer/word_id/question_id。
5. 音效资源：assets/material/word_pk_bgm.mp3、word_pk_feedback_{good,bad,win,lose,draw}.mp3；开关偏好 sp_pk_audio_open。

---
生成：基于 unpacked_dex/restored/dex_out_{2,4,10}.dex 反编译；源码存于 analysis/wordpk_src/。

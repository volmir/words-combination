<template>
    <div>
    
        <h4></h4>
        <h4></h4>

        <div v-if="word && word.length">
            <div v-for="(letter, idx) of word.split('')" v-bind:key="idx" class="letters">{{letter.toUpperCase()}}</div>
        </div>
        
        <div class="statistic">    
            <p class="pull-left">
                <br>
                <i>всего найдено 72 слова, вы отгадали 0</i>
            </p>
        </div>

        <div>
            <form class="form-inline outline" method="post" action="/game">
                <span>Ваш ответ:</span>
                <div class="form-group">
                    <input type="text" class="form-control" placeholder="" id="answerInput" name="answer" value="" maxlength="30" autocomplete="off">
                </div>
                <button class="btn btn-primary" type="submit">Проверить</button>
            </form>
        </div>
 
        <br><br>
        <div class="game_navigation">
            <p class="text-right">
                <a href="#helpModal" data-toggle="modal" class="btn btn-outline-dark btn-sm margin-right">
                    <i class="far fa-question-circle"></i> Показать подсказку
                </a>
                <a href="#statisticModal" data-toggle="modal" class="btn btn-outline-dark btn-sm margin-right">
                    <i class="fas fa-chart-bar"></i> Статистика
                </a>
                <a href="#exitModal" data-toggle="modal" class="btn btn-outline-dark btn-sm margin-right">
                    <i class="fas fa-times"></i> Завершить игру
                </a>
            </p>
        </div>


        <ExitGame/>
        <Statistic/>
        <Description/>
        <Help/>


    </div>
</template>

<script>

import Statistic from './modal/Statistic.vue';
import Description from './modal/Description.vue';
import Help from './modal/Help.vue';
import ExitGame from './modal/ExitGame.vue';

export default {
    components: {
        Statistic,
        Description,
        Help,
        ExitGame
    },
    props: {
        initialWord: {
            type: String,
            default: ''
        }
    },
    data: function() {
        return {
            word: this.initialWord
        }
    },
    created: function () {
        if (this.word) {
            document.title = this.word;            
        }
    }    
}
</script>

<style scoped>
div.letters {
    text-align: center;
    border: 4px solid #B7CCCE;
    width: 30px;
    line-height: 20px;
    border-radius: 0 % ;
    margin: 0px 0px;
    display: inline-block;
    vertical-align: middle;
    font-weight: bold;
    font-size: 16px;
    padding-top: 4px;
    padding-bottom: 3px;
    margin-right: 2px;
    margin-bottom: 2px;
}

form.outline input {
    margin-left: .25rem;
    margin-right: .25rem;
}

</style>
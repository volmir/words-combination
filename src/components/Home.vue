<template>
    <div>
        

                        
<h4>Игра «Составь слова из слов»</h4>

<div v-if="error" class="alert alert-danger alert-dismissible fade show" role="alert">
  Произошла ошибка при загрузке данных
  <button type="button" class="close" v-on:click="closeError" aria-label="Закрыть">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div v-if="success" class="alert alert-success alert-dismissible fade show" role="alert">
  Для начала игры введите корректное слово
  <button type="button" class="close"  v-on:click="closeSuccess" aria-label="Закрыть">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово:</span>
    </p>
    <form class="form-inline" method="post">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" v-model="word" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" v-on:click.stop.prevent="onSubmit" type="submit">Начать игру</button>
    </form>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Начать новую игру (<i>со случайным словом</i>):</span>
    </p>
    <form class="form-inline" method="post" action="/game">
        <template v-if="words && words.length">
            <span v-for="(word, idx) of words" v-bind:key="idx">
                <router-link :to="{name: 'game', params: {initialWord: word.vocab}}" class="btn btn-outline-secondary margin-right">
                    {{word.vocab}}
                </router-link>
            </span>
        </template>
    </form>    
</div>

    <h4>Об игре</h4>
      
<p>    
Игра <b>"Составь слова"</b> предлагает игрокам известную головоломку, в которой нужно 
составлять разные слова из одного длинного слова. 
</p>
<p>
Соревнуйтесь с друзьями в количестве сложенных слов и использованных букв, и узнайте кто более смышлёный.
    </p>


    </div>
</template>

<script>

import axios from 'axios';

export default {
    data: function() {
        return {
            word: '',
            words: [],
            error: false,
            success: false
        }
    },
    methods: {
        onSubmit: function () {
            if (this.word.length) { 
                this.$router.push({ name: 'game', params: {initialWord: this.word}});
            }
        },
        closeError: function () {   
            this.error = false;
        },
        closeSuccess: function () {   
            this.success = false;
        }
    },
    created: function () {
        this.error = false;
        this.success = false;

        axios.get('http://combination-words.local/random/4')
        .then(response => {    
            this.words = Object.values(response.data.data);
            this.error = false; 
        }).catch(e => {
            this.error = e;
        });    
    }
}
</script>

<style scoped>

</style>